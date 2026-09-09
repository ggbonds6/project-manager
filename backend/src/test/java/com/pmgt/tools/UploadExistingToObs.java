package com.pmgt.tools;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import com.obs.services.model.ObjectMetadata;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 一次性工具：把本地 uploads 目录（原本地磁盘附件）按相对路径整体上传到华为 OBS 桶。
 *
 * 设计要点：
 *  - 本地 relKey（YYYY/MM/uuid.ext）即 OBS 对象 key，与库内 attachment.file_path 完全对应 → 无需改库。
 *  - 幂等：OBS 同名对象直接覆盖，可重跑。
 *  - 配置读取环境变量（与后端一致）：APP_STORAGE_OBS_ENDPOINT / BUCKET / AK / SK；
 *    忽略证书校验（validateCertificate=false，自签证书环境）。
 *
 * 用法（OBS 可达的机器上，仓库 backend 目录）：
 *   mvn -o -q test-compile
 *   mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 *   set APP_STORAGE_OBS_ENDPOINT=https://obs.xxx  APP_STORAGE_OBS_BUCKET=pdmsbucket \
 *       APP_STORAGE_OBS_AK=xxx  APP_STORAGE_OBS_SK=xxx
 *   java -cp "target/test-classes;target/classes;$(cat target/cp.txt)" com.pmgt.tools.UploadExistingToObs <本地uploads根目录> [--dry-run]
 */
public class UploadExistingToObs {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: UploadExistingToObs <本地uploads根目录> [--dry-run]");
            System.exit(2);
        }
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();
        boolean dryRun = args.length > 1 && "--dry-run".equals(args[1]);
        if (!Files.isDirectory(root)) {
            System.err.println("目录不存在: " + root);
            System.exit(2);
        }

        String endpoint = System.getenv("APP_STORAGE_OBS_ENDPOINT");
        String bucket = System.getenv("APP_STORAGE_OBS_BUCKET");
        String ak = System.getenv("APP_STORAGE_OBS_AK");
        String sk = System.getenv("APP_STORAGE_OBS_SK");
        if (!dryRun && (isBlank(endpoint) || isBlank(bucket) || isBlank(ak) || isBlank(sk))) {
            System.err.println("缺少环境变量 APP_STORAGE_OBS_ENDPOINT/BUCKET/AK/SK");
            System.exit(2);
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        System.out.println("待上传文件数: " + files.size() + "  根目录: " + root);
        if (dryRun) {
            files.stream().limit(10).forEach(f -> System.out.println("  [dry-run] " + root.relativize(f).toString().replace('\\', '/')));
            return;
        }

        ObsConfiguration conf = new ObsConfiguration();
        conf.setEndPoint(endpoint);
        conf.setPathStyle(true);
        conf.setValidateCertificate(false);
        conf.setConnectionTimeout(15_000);
        conf.setSocketTimeout(120_000);
        AtomicInteger ok = new AtomicInteger();
        List<String> failed = new ArrayList<>();
        try (ObsClient client = new ObsClient(ak, sk, conf)) {
            for (Path f : files) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                try (FileInputStream in = new FileInputStream(f.toFile())) {
                    ObjectMetadata md = new ObjectMetadata();
                    md.setContentLength(f.toFile().length());
                    client.putObject(bucket, rel, in, md);
                    ok.incrementAndGet();
                    if (ok.get() % 50 == 0) {
                        System.out.println("  已上传 " + ok.get() + "/" + files.size());
                    }
                } catch (Exception e) {
                    failed.add(rel + " => " + e.getMessage());
                    System.err.println("  [FAIL] " + rel + " : " + e.getMessage());
                }
            }
        }
        System.out.println("完成: 成功 " + ok.get() + "/" + files.size() + ", 失败 " + failed.size());
        if (!failed.isEmpty()) {
            System.out.println("失败清单(" + failed.size() + "):");
            failed.forEach(System.out::println);
            System.exit(1);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
