package com.pmgt.module.attach.service;

import com.pmgt.common.storage.AttachmentStorage;
import com.pmgt.common.storage.AttachmentStorageProperties;
import com.pmgt.common.storage.ObjectStat;
import com.pmgt.module.attach.AttachmentAvailabilityException;
import com.pmgt.module.attach.dto.AttachmentAvailabilityVO;
import com.pmgt.module.attach.entity.Attachment;
import com.pmgt.module.attach.mapper.AttachmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 附件「当前存储里读不读得到」的批量探测。
 *
 * <h2>它解决的是哪一类真实故障</h2>
 * <p>库是共享的（开发机连的就是生产崖山库），而 {@code app.storage.type=local} 时附件字节只在
 * **本机磁盘**上。于是本机用 local 模式上传的附件，到服务器上必然"文件缺失"
 * （历史事故：412 条孤儿附件，服务器点预览/下载一律 404「附件文件缺失」）。
 * 这个服务让前端在列表阶段就能知道哪些附件在**当前存储**里读不到，从而给出标识并降级交互。
 *
 * <h2>为什么是"探测"而不是"校验"</h2>
 * <p>结论只对**这一次**（最多 60 秒，见下）的存储配置成立：附件可以被重新上传，
 * {@code bucket}/{@code prefix}/upload 根目录也可能被修正。所以：
 * <ul>
 *   <li>不把结论持久化进 {@code attachment} 表（否则修好之后界面永远显示"不可读"）；</li>
 *   <li>不把它当作删除/审计依据；</li>
 *   <li>每次列表加载重新探测一次（同一批 id 在 60 秒内复用内存结论）。</li>
 * </ul>
 *
 * <h2>为什么可以缓存 60 秒，以及缓存键为什么必须带 filePath</h2>
 * <p>OBS 上每条探测是一次 HEAD（{@code getObjectMetadata}），一个项目 50 条附件就是 50 次
 * 往返；用户刷新页面、来回切页签会立刻把同样的 HEAD 重发一遍。附件"存在于某处"这件事在
 * 秒级内几乎不变，因此给<b>本接口</b>单独做 60 秒内存缓存，代价是"文件刚被重新上传/刚被修好时，
 * 最长 60 秒内界面仍显示不可读"——可接受（用户重新上传附件后列表本来就会刷新，且这是提示而非拦截）。
 *
 * <p>缓存键 = {@code id + filePath + 存储类型}，三样缺一不可：
 * <ul>
 *   <li>{@code id}：同一附件一条结论；</li>
 *   <li>{@code filePath}：附件被重新上传/替换后 {@code file_path} 会变（新 uuid），
 *       只用 id 当键会把"旧路径不存在"的结论套到新文件上——这就是漏掉 filePath 会产生的永久脏数据；</li>
 *   <li>存储类型（{@code app.storage.type}）：同一份 file_path 在 {@code local} 与 {@code obs}
 *       下指向完全不同的对象，切换存储后必须重新探测。</li>
 * </ul>
 * 缓存是**纯内存**的：进程重启即清空，不会与任何持久层产生分歧（这正是它敢用的原因）。
 *
 * <h2>不泄漏</h2>
 * <p>响应里只有三种通用中文原因；异常一律不外抛、日志<b>不记异常消息与堆栈</b>
 * （OBS 的异常文案里可能带 endpoint、桶名、对象 key 全名，属于内部信息），只记 id 与异常类型。
 */
@Slf4j
@Service
public class AttachmentAvailabilityService {

    /** 单次探测的 id 上限：一次列表加载 50 条封顶，超出请分批（前端按 50 切块） */
    public static final int MAX_IDS = 50;

    /** 内存结论复用窗口（毫秒）：60 秒，理由见类注释 */
    private static final long CACHE_TTL_MILLIS = 60_000L;

    /** 缓存条目上限：超过就整体清空（探测结论可以随时重算，不值得为它做 LRU） */
    private static final int MAX_CACHE_ENTRIES = 2_000;

    /** 通用原因：存储里读不到该附件的字节（本地文件不存在 / OBS 对象不存在 / 存储不可达） */
    public static final String REASON_MISSING_IN_STORAGE = "当前存储中不存在该附件文件";

    /** 通用原因：附件元数据本身查不到（已逻辑删除 / id 不存在） */
    public static final String REASON_MISSING_RECORD = "附件记录不存在（可能已被删除）";

    private final AttachmentMapper attachmentMapper;
    private final AttachmentStorage attachmentStorage;
    private final AttachmentStorageProperties storageProperties;

    /** key = id + filePath + 存储类型（见类注释），value = 结论 + 到期时刻 */
    private final Map<String, CachedProbe> cache = new ConcurrentHashMap<>();

    public AttachmentAvailabilityService(AttachmentMapper attachmentMapper,
                                         AttachmentStorage attachmentStorage,
                                         AttachmentStorageProperties storageProperties) {
        this.attachmentMapper = attachmentMapper;
        this.attachmentStorage = attachmentStorage;
        this.storageProperties = storageProperties;
    }

    /**
     * 解析并校验 {@code ids}（逗号分隔）。{@code attachmentIds} 是同义别名，由调用方先做兜底。
     *
     * @throws AttachmentAvailabilityException 空 / 非正整数 / 去重后超过 {@link #MAX_IDS} 个
     */
    public List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AttachmentAvailabilityException("ids 不能为空，形如 ?ids=1,2,3");
        }
        // LinkedHashSet：顺序与请求一致，且重复 id 只探测一次（重复不等于错误）
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(text);
            } catch (NumberFormatException e) {
                // 不回显入参：它可能是任意字符串（含超长串），没必要进响应
                throw new AttachmentAvailabilityException("ids 必须是逗号分隔的正整数，形如 ?ids=1,2,3");
            }
            if (id <= 0) {
                throw new AttachmentAvailabilityException("ids 必须是逗号分隔的正整数，形如 ?ids=1,2,3");
            }
            ids.add(id);
        }
        if (ids.isEmpty()) {
            throw new AttachmentAvailabilityException("ids 不能为空，形如 ?ids=1,2,3");
        }
        if (ids.size() > MAX_IDS) {
            throw new AttachmentAvailabilityException(
                    "一次最多探测 " + MAX_IDS + " 个附件 id（本次 " + ids.size() + " 个），请分批查询");
        }
        return new ArrayList<>(ids);
    }

    /**
     * 逐条探测；<b>单条失败不影响其它条</b>（异常 → 该条 available=false + 通用原因）。
     *
     * <p>元数据一次批量查（{@code selectBatchIds}），不循环查库；存储侧每个 id 一次
     * {@code stat}，命中 60 秒缓存则不发请求。
     */
    public AttachmentAvailabilityVO check(List<Long> ids) {
        List<Attachment> rows = attachmentMapper.selectBatchIds(ids);
        Map<Long, Attachment> byId = rows.stream()
                .collect(Collectors.toMap(Attachment::getId, Function.identity(), (a, b) -> a));

        AttachmentAvailabilityVO result = new AttachmentAvailabilityVO();
        List<AttachmentAvailabilityVO.Item> items = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Attachment attachment = byId.get(id);
            if (attachment == null) {
                items.add(item(id, false, null, REASON_MISSING_RECORD));
                continue;
            }
            items.add(probe(id, attachment.getFilePath()));
        }
        result.setItems(items);
        return result;
    }

    /** 单条探测：一次 stat（或 60 秒内的缓存结论）；异常只影响这一条 */
    private AttachmentAvailabilityVO.Item probe(Long id, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            // 元数据在但没登记 file_path（历史脏行）：同样按"存储里没有"处理，不抛异常
            return item(id, false, null, REASON_MISSING_IN_STORAGE);
        }
        String key = cacheKey(id, filePath);
        CachedProbe cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
            return cached.toItem(id);
        }

        ObjectStat stat;
        try {
            stat = attachmentStorage.stat(filePath);
        } catch (RuntimeException e) {
            // 单条失败不影响其它条：不缓存这个结论（网络抖动不该让界面在 60 秒内一直显示"不可读"）
            log.warn("附件可用性探测异常 id={} 存储类型={} 异常类型={}",
                    id, storageType(), e.getClass().getSimpleName());
            return item(id, false, null, REASON_MISSING_IN_STORAGE);
        }

        CachedProbe probe = stat == null
                ? new CachedProbe(false, null, REASON_MISSING_IN_STORAGE, System.currentTimeMillis() + CACHE_TTL_MILLIS)
                : new CachedProbe(true, stat.size(), null, System.currentTimeMillis() + CACHE_TTL_MILLIS);
        put(key, probe);
        return probe.toItem(id);
    }

    private void put(String key, CachedProbe probe) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            // 整体清空而不是逐条淘汰：探测结论随时可重算，多留一个过期窗口比多一份 LRU 逻辑便宜
            cache.clear();
        }
        cache.put(key, probe);
    }

    private String cacheKey(Long id, String filePath) {
        return id + "|" + filePath + "|" + storageType();
    }

    /**
     * 当前存储类型（{@code local} / {@code obs}）。缓存键的一部分：同一 file_path 在两种存储下
     * 指向完全不同的对象，切换存储后必须重新探测。
     */
    private String storageType() {
        String type = storageProperties == null ? null : storageProperties.getType();
        return type == null || type.isBlank() ? "local" : type;
    }

    private static AttachmentAvailabilityVO.Item item(Long id, boolean available, Long size, String reason) {
        AttachmentAvailabilityVO.Item item = new AttachmentAvailabilityVO.Item();
        item.setId(id);
        item.setAvailable(available);
        item.setSize(size);
        item.setReason(reason);
        return item;
    }

    /** 60 秒内存结论（不可变）：重启即失，不与持久层对账（见类注释） */
    private record CachedProbe(boolean available, Long size, String reason, long expiresAtMillis) {

        AttachmentAvailabilityVO.Item toItem(Long id) {
            return item(id, available, size, reason);
        }
    }
}
