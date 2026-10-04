package com.eyemonitor.ui.chat;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.ui.theme.ChatTheme;
import com.eyemonitor.ui.WaveformView;

import static com.eyemonitor.ui.chat.ChatItem.TYPE_MEDIA_PEER;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_MEDIA_SELF;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_PEER;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_SELF;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_SYSTEM;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_TASK_PEER;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_TASK_SELF;

/** 聊天列表适配器（⑧ 切片重构：从 MainActivity 迁出，宿主动作经 Host 接口回调，单向依赖）。 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    /** MainActivity 宿主门面：渲染所需的上下文/资源/动作回调 */
    public interface Host {
        int dp(float v);
        String getString(int res);
        String getString(int res, Object... args);
        int getColor(int res);
        Resources getResources();
        PrefsManager getPrefs();
        ChatTheme theme();
        void showChatItemActions(ChatItem item, View bubble);
        void resendChat(ChatItem item);
        void scrollToRef(long refMsgId);
        void flashRow(View row, ChatItem item);
        CharSequence styleSystemText(ChatItem item);
        void bindTask(ViewHolder h, ChatItem item);
        void bindMedia(ViewHolder h, ChatItem item, int position);
    }

    private final Host host;
    private final java.util.List<ChatItem> items = new java.util.ArrayList<>();

    public ChatAdapter(Host host) {
        this.host = host;
    }

    public void addItem(ChatItem item) {
        items.add(item);
        notifyItemInserted(items.size() - 1);
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    public int getCount() {
        return items.size();
    }

    public java.util.List<ChatItem> getItems() {
        return items;
    }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View view;
            switch (viewType) {
                case TYPE_SELF:
                    view = inflater.inflate(R.layout.item_chat_self, parent, false);
                    break;
                case TYPE_PEER:
                    view = inflater.inflate(R.layout.item_chat_peer, parent, false);
                    break;
                case TYPE_MEDIA_SELF:
                case TYPE_MEDIA_PEER:
                    view = inflater.inflate(R.layout.item_chat_media, parent, false);
                    break;
                case TYPE_TASK_SELF:
                case TYPE_TASK_PEER:
                    view = inflater.inflate(R.layout.item_task, parent, false);
                    break;
                default:
                    view = inflater.inflate(R.layout.item_chat_system, parent, false);
                    break;
            }
            return new ViewHolder(view, viewType);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            ChatItem item = items.get(position);
            holder.bind(item, position);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            public final int viewType;
            public TextView tvText;
            public android.widget.FrameLayout llBubbleDecor;
            public android.widget.FrameLayout llBubbleBox;
            public android.widget.ImageView ivBubbleEar;
            public android.widget.ImageView ivBubblePaw;
            public TextView tvTime;
            public TextView tvFrom;
            public TextView tvRead;
            public TextView tvRefName;
            public TextView tvRefText;
            public LinearLayout llChatRef;
            public TextView tvRecalled;
            public TextView tvSendStatus;
            // 媒体气泡视图
            public LinearLayout llMediaBubble;
            public FrameLayout flMediaContainer;
            public ImageView ivMediaThumb;
            public ImageView ivMediaPlaceholderIcon;
            public LinearLayout llMediaPlaceholder;
            public TextView tvMediaHint;
            public LinearLayout llVoiceBubble;
            public ImageView ivVoiceWifi;
            public WaveformView waveformView;
            public TextView tvVoiceDuration;
            public FrameLayout flVideoBadge;
            // 任务气泡视图
            public LinearLayout llTaskCard;
            public ImageView ivTaskBubbleIcon;
            public TextView tvTaskTitle;
            public TextView tvTaskTime;
            public View vTaskDivider1;
            public View vTaskDivider2;
            public TextView tvTaskContent;
            public LinearLayout llTaskPhotos;
            public TextView tvTaskReward;
            public TextView tvTaskStatus;
            public TextView tvTaskHint;

            ViewHolder(View view, int viewType) {
                super(view);
                this.viewType = viewType;
                switch (viewType) {
                    case TYPE_SELF:
                        tvText = view.findViewById(R.id.tv_chat_text);
                        llBubbleDecor = (android.widget.FrameLayout) view.findViewById(R.id.ll_bubble_decor);
                        llBubbleBox = (android.widget.FrameLayout) view.findViewById(R.id.ll_bubble_box);
                        ivBubbleEar = view.findViewById(R.id.iv_bubble_ear);
                        ivBubblePaw = view.findViewById(R.id.iv_bubble_paw);
                        tvTime = view.findViewById(R.id.tv_chat_time);
                        tvRead = view.findViewById(R.id.tv_chat_read);
                        llChatRef = view.findViewById(R.id.ll_chat_ref);
                        tvRefName = view.findViewById(R.id.tv_ref_name);
                        tvRefText = view.findViewById(R.id.tv_ref_text);
                        tvRecalled = view.findViewById(R.id.tv_recalled);
                        tvSendStatus = view.findViewById(R.id.tv_send_status);
                        break;
                    case TYPE_PEER:
                        tvText = view.findViewById(R.id.tv_chat_text);
                        llBubbleDecor = (android.widget.FrameLayout) view.findViewById(R.id.ll_bubble_decor);
                        llBubbleBox = (android.widget.FrameLayout) view.findViewById(R.id.ll_bubble_box);
                        ivBubbleEar = view.findViewById(R.id.iv_bubble_ear);
                        ivBubblePaw = view.findViewById(R.id.iv_bubble_paw);
                        tvTime = view.findViewById(R.id.tv_chat_time);
                        tvFrom = view.findViewById(R.id.tv_chat_from);
                        llChatRef = view.findViewById(R.id.ll_chat_ref);
                        tvRefName = view.findViewById(R.id.tv_ref_name);
                        tvRefText = view.findViewById(R.id.tv_ref_text);
                        tvRecalled = view.findViewById(R.id.tv_recalled);
                        break;
                    case TYPE_MEDIA_SELF:
                    case TYPE_MEDIA_PEER:
                        llMediaBubble = view.findViewById(R.id.ll_media_bubble);
                        llBubbleDecor = (android.widget.FrameLayout) view.findViewById(R.id.ll_bubble_decor);
                        llBubbleBox = (android.widget.FrameLayout) view.findViewById(R.id.ll_bubble_box);
                        ivBubbleEar = view.findViewById(R.id.iv_bubble_ear);
                        ivBubblePaw = view.findViewById(R.id.iv_bubble_paw);
                        flMediaContainer = view.findViewById(R.id.fl_media_container);
                        ivMediaThumb = view.findViewById(R.id.iv_media_thumb);
                        ivMediaPlaceholderIcon = view.findViewById(R.id.iv_media_placeholder_icon);
                        llMediaPlaceholder = view.findViewById(R.id.ll_media_placeholder);
                        llVoiceBubble = view.findViewById(R.id.ll_voice_bubble);
                        ivVoiceWifi = view.findViewById(R.id.iv_voice_wifi);
                        waveformView = view.findViewById(R.id.waveform_view);
                        tvVoiceDuration = view.findViewById(R.id.tv_voice_duration);
                        tvMediaHint = view.findViewById(R.id.tv_media_hint);
                        flVideoBadge = view.findViewById(R.id.fl_video_badge);
                        tvTime = view.findViewById(R.id.tv_chat_time);
                        tvFrom = view.findViewById(R.id.tv_chat_from);
                        break;
                    case TYPE_TASK_SELF:
                    case TYPE_TASK_PEER:
                        llTaskCard = view.findViewById(R.id.ll_task_card);
                        ivTaskBubbleIcon = view.findViewById(R.id.iv_task_bubble_icon);
                        tvTaskTitle = view.findViewById(R.id.tv_task_title);
                        tvTaskTime = view.findViewById(R.id.tv_task_time);
                        vTaskDivider1 = view.findViewById(R.id.v_task_divider1);
                        vTaskDivider2 = view.findViewById(R.id.v_task_divider2);
                        tvTaskContent = view.findViewById(R.id.tv_task_content);
                        llTaskPhotos = view.findViewById(R.id.ll_bubble_photos);
                        tvTaskReward = view.findViewById(R.id.tv_task_reward);
                        tvTaskStatus = view.findViewById(R.id.tv_task_status);
                        tvTaskHint = view.findViewById(R.id.tv_task_hint);
                        break;
                    default:
                        tvText = view.findViewById(R.id.tv_system_text);
                        break;
                }
            }

            void bind(ChatItem item) {
                bind(item, getBindingAdapterPosition());
            }

            /** 撤回态：气泡/引用/已读/时间隐藏，居中系统提示“X 撤回了一条消息” */
            void boxHiddenForRecalled(boolean recalled, boolean peer) {
                tvText.setVisibility(recalled ? View.GONE : View.VISIBLE);
                llChatRef.setVisibility(View.GONE);
                int density = (int) host.getResources().getDisplayMetrics().density;
                if (peer) {
                    tvFrom.setVisibility(recalled ? View.GONE : View.VISIBLE);
                    tvTime.setVisibility(recalled ? View.GONE : View.VISIBLE);
                } else {
                    tvRead.setVisibility(View.GONE);
                    View timeRow = (View) tvTime.getParent();
                    if (timeRow != null) timeRow.setVisibility(recalled ? View.GONE : View.VISIBLE);
                }
                int pad = (int) (4 * density);
                if (recalled) {
                    // 系统提示居中：两侧内边距对称
                    itemView.setPadding(12 * density, pad, 12 * density, pad);
                } else {
                    final int outer = 60 * density, near = 12 * density;
                    itemView.setPadding(peer ? near : outer, pad, peer ? outer : near, pad);
                }
            }

            void bindQuote(ChatItem item) {
                if (item.refMsgId > 0 && item.refText != null) {
                    llChatRef.setVisibility(View.VISIBLE);
                    tvRefName.setText(refSenderName(item));
                    tvRefText.setText(item.refText);
                    llChatRef.setOnClickListener(v -> host.scrollToRef(item.refMsgId));
                } else {
                    llChatRef.setVisibility(View.GONE);
                }
            }

            /** 被引用消息的发送者名：自己→「我」；对方→其昵称/备注；找不到→「引用」 */
            String refSenderName(ChatItem item) {
                for (ChatItem it : items) {
                    if (it.ts == item.refMsgId) {
                        if (it.type == TYPE_SELF || it.type == TYPE_MEDIA_SELF) {
                            return host.getString(R.string.quote_sender_me);
                        }
                        String display = host.getPrefs().getPeerDisplayName();
                        String from = display != null ? display
                                : (it.from != null && !it.from.isEmpty()
                                ? it.from : host.getPrefs().getPeerNickname());
                        return from != null && !from.isEmpty()
                                ? from : host.getString(R.string.quote_sender_unknown);
                    }
                }
                return host.getString(R.string.quote_sender_unknown);
            }

            String whoSent(ChatItem item) {
                String display = host.getPrefs().getPeerDisplayName();
                String from = display != null ? display
                        : (item.from != null && !item.from.isEmpty()
                        ? item.from : host.getPrefs().getPeerNickname());
                return from != null && !from.isEmpty()
                        ? from : host.getString(R.string.chat_title_default);
            }


            /** 气泡悬浮装饰（耳朵/狗爪）：仅狗狗主题显示；顶部留耳高悬浮区，数值=你的调节旋钮 */
            /** 气泡装饰：耳=根级悬浮（浮出气泡顶），爪=包气泡容器底角；仅狗狗主题显示 */
            /** 气泡装饰：耳=包气泡容器顶角浮出（self 左上 / peer 右上），爪=容器底角 */
            public void bindBubbleDecor(boolean self) {
                if (llBubbleDecor == null || llBubbleBox == null) return;
                com.eyemonitor.ui.theme.ChatTheme t = host.theme();
                if (com.eyemonitor.ui.theme.ChatTheme.ID_DOG.equals(t.id)) {
                    // 悬浮头房区设在包气泡容器上（锚定气泡本体）；裁剪双关，否则 padding 起点裁掉耳朵
                    int headroom = host.dp(14);
                    llBubbleBox.setPadding(0, headroom, 0, 0);
                    llBubbleBox.setClipChildren(false);
                    llBubbleBox.setClipToPadding(false);
                    // 根容器与 RecyclerView 的裁剪链也关掉（耳浮出条目标界时兜底）
                    llBubbleDecor.setClipChildren(false);
                    llBubbleDecor.setClipToPadding(false);
                    llBubbleDecor.setPadding(0, 0, 0, 0);
                    android.view.ViewParent vp = llBubbleDecor.getParent();
                    while (vp != null && !(vp instanceof androidx.recyclerview.widget.RecyclerView)) {
                        if (vp instanceof android.view.ViewGroup) ((android.view.ViewGroup) vp).setClipChildren(false);
                        vp = vp.getParent();
                    }
                    if (vp instanceof androidx.recyclerview.widget.RecyclerView) {
                        ((androidx.recyclerview.widget.RecyclerView) vp).setClipChildren(false);
                        ((androidx.recyclerview.widget.RecyclerView) vp).setClipToPadding(false);
                    }
                    // 耳：self=气泡左上 / peer=气泡右上，负 margin 浮过气泡顶
                    if (ivBubbleEar != null) {
                        ivBubbleEar.setVisibility(View.VISIBLE);
                        ivBubbleEar.setImageResource(self
                                ? R.drawable.bubble_ear_self : R.drawable.bubble_ear_peer);
                        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams)
                                ivBubbleEar.getLayoutParams();
                        lp.gravity = self
                                ? (android.view.Gravity.TOP | android.view.Gravity.START)
                                : (android.view.Gravity.TOP | android.view.Gravity.END);
                        // 垂直：margin（已验证在带 padding 容器内可靠）；水平：translationX（负 margin 会被测量吃掉，禁用）
                        lp.setMargins(0, -host.dp(14), 0, 0);   // 上移1（-13→-14）
                        ivBubbleEar.setTranslationX(self ? -host.dp(3) : host.dp(3));   // 对面耳右移1（+2→+3）
                        ivBubbleEar.setLayoutParams(lp);
                    }
                    // 爪：self=右下 / peer=左下
                    if (ivBubblePaw != null) {
                        ivBubblePaw.setVisibility(View.VISIBLE);
                        ivBubblePaw.setImageResource(self
                                ? R.drawable.bubble_paw_self : R.drawable.bubble_paw_peer);
                        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams)
                                ivBubblePaw.getLayoutParams();
                        lp.gravity = self
                                ? (android.view.Gravity.BOTTOM | android.view.Gravity.END)
                                : (android.view.Gravity.BOTTOM | android.view.Gravity.START);
                        ivBubblePaw.setLayoutParams(lp);
                        ivBubblePaw.setTranslationX(self ? -host.dp(3) : host.dp(3));   // 爪左移3（peer 镜像右移）
                        ivBubblePaw.setTranslationY(-host.dp(3));                   // 爪上移3
                    }
                } else {
                    llBubbleDecor.setPadding(0, 0, 0, 0);
                    if (llBubbleBox != null) llBubbleBox.setPadding(0, 0, 0, 0);
                    if (ivBubbleEar != null) ivBubbleEar.setVisibility(View.GONE);
                    if (ivBubblePaw != null) ivBubblePaw.setVisibility(View.GONE);
                }
            }

            void bindItemLongPress(ChatItem item) {
                itemView.setOnLongClickListener(v -> {
                    if (item.deleted) return true;
                    host.showChatItemActions(item, tvText);
                    return true;
                });
            }

            void bind(ChatItem item, int position) {
                if (item.flash) {
                    host.flashRow(itemView, item);
                }
                switch (viewType) {
                    case TYPE_SELF:
                        if (item.deleted) {
                            tvRecalled.setVisibility(View.VISIBLE);
                            tvRecalled.setText(R.string.chat_recalled_self);
                            boxHiddenForRecalled(true, false);
                        } else {
                            tvRecalled.setVisibility(View.GONE);
                            boxHiddenForRecalled(false, false);
                            // 主题：气泡背景运行时切换（一套布局不复制）
                            tvText.setBackgroundResource(
                                    host.theme().bubbleSelfRes);
                            // 主题：self 文字色（PNG 浅底气泡用深字，默认珊瑚底白字）
                            tvText.setTextColor(host.getColor(
                                    host.theme().selfTextColorRes));
                            bindBubbleDecor(true);
                            tvText.setText(item.text);
                            tvTime.setText(item.time);
                            // 送达状态：失败标红「未送达」可点击重发；成功则隐藏
                            if (item.failed) {
                                tvSendStatus.setVisibility(View.VISIBLE);
                                tvRead.setVisibility(View.GONE);
                                tvSendStatus.setOnClickListener(v -> host.resendChat(item));
                            } else {
                                tvSendStatus.setVisibility(View.GONE);
                                tvRead.setVisibility(item.peerRead ? View.VISIBLE : View.GONE);
                            }
                            bindQuote(item);
                        }
                        bindItemLongPress(item);
                        break;
                    case TYPE_PEER:
                        if (item.deleted) {
                            tvRecalled.setVisibility(View.VISIBLE);
                            tvRecalled.setText(host.getString(R.string.chat_recalled_peer,
                                    whoSent(item)));
                            boxHiddenForRecalled(true, true);
                        } else {
                            tvRecalled.setVisibility(View.GONE);
                            boxHiddenForRecalled(false, true);
                            // 主题：气泡背景运行时切换（一套布局不复制）
                            tvText.setBackgroundResource(
                                    host.theme().bubblePeerRes);
                            bindBubbleDecor(false);
                            tvText.setText(item.text);
                            tvTime.setText(item.time);
                            tvFrom.setText(whoSent(item));
                            bindQuote(item);
                        }
                        bindItemLongPress(item);
                        break;
                    case TYPE_MEDIA_SELF:
                    case TYPE_MEDIA_PEER:
                        host.bindMedia(this, item, position);
                        break;
                    case TYPE_TASK_SELF:
                    case TYPE_TASK_PEER:
                        host.bindTask(this, item);
                        break;
                    default:
                        tvText.setText(host.styleSystemText(item));
                        break;
                }
            }
        }
    }
