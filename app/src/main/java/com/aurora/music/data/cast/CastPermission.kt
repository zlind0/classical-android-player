package com.aurora.music.data.cast

/** 接收端对一次投屏请求的处理方式。 */
enum class CastAdmission {
    /** 直接开始接收，不打扰用户。 */
    Allow,

    /** 弹接收端确认浮层，由用户决定接受或拒绝。 */
    Ask,

    /** 直接拒绝，发送端会收到“正忙/不可投”。 */
    Deny,
}

/**
 * 投屏接收的准入策略。
 *
 * 目前投屏没有真正的权限管理：只要请求合法、发送端不是自己，就一律允许。
 * 因此接收端收到 `/cast/offer` 后会直接进入接收，不再弹「接受/拒绝」浮层。
 *
 * TODO(权限管理): 以后若引入新的权限管理（例如按发送端设备维护信任列表、
 * 音乐/电子书分开授权、陌生设备每次询问等），只需在 [decide] 里按
 * [CastOffer.senderId] / [CastOffer.contentType] 等返回 [CastAdmission.Ask]
 * 或 [CastAdmission.Deny]；接收端的 [CastReceiveState.Incoming] 确认浮层
 * 仍然保留着，会自然重新生效，不需要改动投屏协议。
 */
object CastPermission {
    fun decide(offer: CastOffer): CastAdmission {
        // TODO(权限管理): 现在是“总是允许”，非法请求除外。
        if (!offer.valid) return CastAdmission.Deny
        return CastAdmission.Allow
    }
}
