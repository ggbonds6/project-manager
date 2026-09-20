package com.pmgt.module.ai.service;

import com.pmgt.common.exception.BizException;

import java.util.List;

/**
 * 作用域/权限拒绝（前端传了不可访问的 id）。
 *
 * <p>为什么不用普通的 400/404 直接拒绝：
 * <ul>
 *   <li>「不是你的附件」是 <b>403</b>，与「这个 id 不存在」（404）在排障时含义完全不同，
 *       服务端日志混在一起就查不清是前端传错了还是有人在试探；</li>
 *   <li>方案要求「前端传不可访问的 id 要拒绝（不是静默过滤，给出明确错误）」，
 *       所以错误里要能带上被拒绝的 id 与原因，而不是一句「参数错误」。</li>
 * </ul>
 */
public class AiScopeDeniedException extends BizException {

    /** 403：登录了，但这次请求的范围里有不该访问的东西。 */
    public static final int CODE = 403;

    public AiScopeDeniedException(String message) {
        super(CODE, message);
    }

    /** 由「不可访问 / 不可检索」的明细拼一条可操作的提示。 */
    public static AiScopeDeniedException of(List<String> problems) {
        return new AiScopeDeniedException("无权访问或不可问答的作用域：" + String.join("；", problems));
    }
}
