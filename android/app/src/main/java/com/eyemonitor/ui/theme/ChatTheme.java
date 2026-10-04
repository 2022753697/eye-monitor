package com.eyemonitor.ui.theme;

import com.eyemonitor.R;

/**
 * 主题定义（好感动等级系统装扮区）。
 * <p>
 * 数据驱动资源映射：每主题一组聊天域资源 + 全局彩蛋，运行时用
 * setBackgroundResource / setImageResource 切换，一套 item_chat_self/peer 布局不复制。
 * 解锁共同（等级 pair 级）、应用各自（选择存 PrefsManager 本地偏好）。
 */
public class ChatTheme {

    public static final String ID_DEFAULT = "default";
    public static final String ID_DOG = "dog";

    /** 主题 id（PrefsManager 持久化键值） */
    public final String id;
    /** 主题名（字符串资源） */
    public final int nameRes;
    /** 解锁等级（1 = 默认全解锁） */
    public final int unlockLevel;

    // --- 聊天域资源组 ---
    /** 头栏渐变 */
    public final int headerGradientRes;
    /** 头栏爪印装饰（0 = 无） */
    public final int headerDecorRes;
    /** 聊天页背景（color 或 drawable） */
    public final int chatBgRes;
    /** 自己气泡 */
    public final int bubbleSelfRes;
    /** 对方气泡 */
    public final int bubblePeerRes;
    /** self 气泡文字色（PNG 浅底气泡需深字；默认珊瑚白字） */
    public final int selfTextColorRes;
    /** 输入区背景 */
    public final int inputBgRes;
    /** 按住说话条背景 */
    public final int voiceBarBgRes;
    /** 发送键背景（0 = 无，用图标自带底） */
    public final int sendBgRes;
    /** 发送键图标 */
    public final int sendIconRes;
    /** 发送键是否自带配色（true 时不做 tint） */
    public final boolean customSendIcon;
    /** 头栏按钮图标集（地图/图库；自带配色时不做 tint） */
    public final int iconMapRes;
    public final int iconGalleryRes;
    /** 输入区图标集（语音/表情/更多） */
    public final int iconMicRes;
    public final int iconEmojiRes;
    public final int iconMoreRes;
    /** 是否自带配色图标集（true 时全部不做 tint） */
    public final boolean customIcons;
    /** 空态插画（0 = 无） */
    public final int emptyStateRes;
    /** 主题预览卡布局 */
    public final int previewLayoutRes;

    // --- 全局彩蛋资源组 ---
    /** 升级动画粒子配色（integer-array 资源） */
    public final int levelUpColorsRes;
    /** 升级/提醒通知小图标 */
    public final int notificationIconRes;
    /** Lv.5 爪印 marker pin（0 = 用默认头像 pin） */
    public final int markerPinRes;
    /** 升级粒子是否带爪印元素 */
    public final boolean pawParticles;

    private ChatTheme(Builder b) {
        this.id = b.id;
        this.nameRes = b.nameRes;
        this.unlockLevel = b.unlockLevel;
        this.headerGradientRes = b.headerGradientRes;
        this.headerDecorRes = b.headerDecorRes;
        this.chatBgRes = b.chatBgRes;
        this.bubbleSelfRes = b.bubbleSelfRes;
        this.bubblePeerRes = b.bubblePeerRes;
        this.selfTextColorRes = b.selfTextColorRes;
        this.inputBgRes = b.inputBgRes;
        this.voiceBarBgRes = b.voiceBarBgRes;
        this.sendBgRes = b.sendBgRes;
        this.sendIconRes = b.sendIconRes;
        this.customSendIcon = b.customSendIcon;
        this.iconMapRes = b.iconMapRes;
        this.iconGalleryRes = b.iconGalleryRes;
        this.iconMicRes = b.iconMicRes;
        this.iconEmojiRes = b.iconEmojiRes;
        this.iconMoreRes = b.iconMoreRes;
        this.customIcons = b.customIcons;
        this.emptyStateRes = b.emptyStateRes;
        this.previewLayoutRes = b.previewLayoutRes;
        this.levelUpColorsRes = b.levelUpColorsRes;
        this.notificationIconRes = b.notificationIconRes;
        this.markerPinRes = b.markerPinRes;
        this.pawParticles = b.pawParticles;
    }

    /** 默认主题（珊瑚恋语：品牌视觉体系原样） */
    public static ChatTheme buildDefault() {
        return new ChatTheme.Builder()
                .id(ID_DEFAULT)
                .nameRes(R.string.theme_default_name)
                .unlockLevel(1)
                .headerGradientRes(R.drawable.bg_header_gradient)
                .headerDecorRes(0)
                .chatBgRes(R.color.background)
                .bubbleSelfRes(R.drawable.bg_bubble_self)
                .bubblePeerRes(R.drawable.bg_bubble_peer)
                .selfTextColorRes(R.color.text_on_primary)
                .inputBgRes(R.drawable.bg_input)
                .voiceBarBgRes(R.drawable.bg_voice_bar)
                .sendBgRes(R.drawable.bg_icon_circle)
                .sendIconRes(R.drawable.ic_send)
                .customSendIcon(false)
                .iconMapRes(R.drawable.ic_map)
                .iconGalleryRes(R.drawable.ic_gallery)
                .iconMicRes(R.drawable.ic_mic)
                .iconEmojiRes(R.drawable.ic_emoji)
                .iconMoreRes(R.drawable.ic_more)
                .customIcons(false)
                .emptyStateRes(0)
                .previewLayoutRes(R.layout.item_theme_preview)
                .levelUpColorsRes(R.array.level_up_colors_default)
                .notificationIconRes(R.drawable.ic_heart)
                .markerPinRes(0)
                .pawParticles(false)
                .build();
    }

    /** 狗狗乐园（Lv.3 解锁：珊瑚粉系·更圆，全套皮肤 + 全局彩蛋） */
    public static ChatTheme buildDog() {
        return new ChatTheme.Builder()
                .id(ID_DOG)
                .nameRes(R.string.theme_dog_name)
                .unlockLevel(3)
                .headerGradientRes(R.drawable.bg_header_dog_raw)   // 头栏底图 v5
                .headerDecorRes(0)   // 爪印暂不显示（不占位），等手绘图直接贴
                .chatBgRes(R.drawable.bg_chat_dog)
                .bubbleSelfRes(R.drawable.bubble_frame_dog_self)
                .bubblePeerRes(R.drawable.bubble_frame_dog_peer)
                .selfTextColorRes(R.color.text_primary)
                .inputBgRes(R.drawable.bg_input_dog)   // 输入框：白底珊瑚描边（狗爪已撤）
                .voiceBarBgRes(R.drawable.bg_voice_bar_dog)
                .sendBgRes(0)
                .sendIconRes(R.drawable.theme_dog_btn_send)   // 手绘发送钮 PNG
                .customSendIcon(true)
                .iconMapRes(R.drawable.theme_dog_ic_map)
                .iconGalleryRes(R.drawable.theme_dog_ic_gallery)
                .iconMicRes(R.drawable.theme_dog_ic_mic)
                .iconEmojiRes(R.drawable.theme_dog_ic_emoji)
                .iconMoreRes(R.drawable.theme_dog_ic_more)
                .customIcons(true)
                .emptyStateRes(R.drawable.ic_dog_empty)
                .previewLayoutRes(R.layout.item_theme_preview)
                .levelUpColorsRes(R.array.level_up_colors_dog)
                .notificationIconRes(R.drawable.ic_paw_white)
                .markerPinRes(R.drawable.marker_pin_lv5)
                .pawParticles(true)
                .build();
    }

    public static class Builder {
        private String id;
        private int nameRes;
        private int unlockLevel = 1;
        private int headerGradientRes;
        private int headerDecorRes;
        private int chatBgRes;
        private int bubbleSelfRes;
        private int bubblePeerRes;
        private int selfTextColorRes = R.color.text_on_primary;
        private int inputBgRes;
        private int voiceBarBgRes;
        private int sendBgRes;
        private int sendIconRes;
        private boolean customSendIcon;
        private int iconMapRes;
        private int iconGalleryRes;
        private int iconMicRes;
        private int iconEmojiRes;
        private int iconMoreRes;
        private boolean customIcons;
        private int emptyStateRes;
        private int previewLayoutRes;
        private int levelUpColorsRes;
        private int notificationIconRes;
        private int markerPinRes;
        private boolean pawParticles;

        public Builder id(String v) { this.id = v; return this; }
        public Builder nameRes(int v) { this.nameRes = v; return this; }
        public Builder unlockLevel(int v) { this.unlockLevel = v; return this; }
        public Builder headerGradientRes(int v) { this.headerGradientRes = v; return this; }
        public Builder headerDecorRes(int v) { this.headerDecorRes = v; return this; }
        public Builder chatBgRes(int v) { this.chatBgRes = v; return this; }
        public Builder bubbleSelfRes(int v) { this.bubbleSelfRes = v; return this; }
        public Builder bubblePeerRes(int v) { this.bubblePeerRes = v; return this; }
        public Builder selfTextColorRes(int v) { this.selfTextColorRes = v; return this; }
        public Builder inputBgRes(int v) { this.inputBgRes = v; return this; }
        public Builder voiceBarBgRes(int v) { this.voiceBarBgRes = v; return this; }
        public Builder sendBgRes(int v) { this.sendBgRes = v; return this; }
        public Builder sendIconRes(int v) { this.sendIconRes = v; return this; }
        public Builder customSendIcon(boolean v) { this.customSendIcon = v; return this; }
        public Builder iconMapRes(int v) { this.iconMapRes = v; return this; }
        public Builder iconGalleryRes(int v) { this.iconGalleryRes = v; return this; }
        public Builder iconMicRes(int v) { this.iconMicRes = v; return this; }
        public Builder iconEmojiRes(int v) { this.iconEmojiRes = v; return this; }
        public Builder iconMoreRes(int v) { this.iconMoreRes = v; return this; }
        public Builder customIcons(boolean v) { this.customIcons = v; return this; }
        public Builder emptyStateRes(int v) { this.emptyStateRes = v; return this; }
        public Builder previewLayoutRes(int v) { this.previewLayoutRes = v; return this; }
        public Builder levelUpColorsRes(int v) { this.levelUpColorsRes = v; return this; }
        public Builder notificationIconRes(int v) { this.notificationIconRes = v; return this; }
        public Builder markerPinRes(int v) { this.markerPinRes = v; return this; }
        public Builder pawParticles(boolean v) { this.pawParticles = v; return this; }

        public ChatTheme build() {
            return new ChatTheme(this);
        }
    }
}