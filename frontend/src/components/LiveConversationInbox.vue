<template>
  <div class="omnichannel-inbox-layout" :class="'mobile-pane-' + mobilePane">
    <!-- =================================================================== -->
    <!-- MOBILE 3-TAB NAVIGATION BAR (Visible only on <= 960px)              -->
    <!-- =================================================================== -->
    <nav class="mobile-inbox-tabs" aria-label="Điều hướng Hộp thư trên điện thoại">
      <button
        type="button"
        class="mobile-inbox-tab"
        :class="{ active: mobilePane === 'list' }"
        @click="mobilePane = 'list'"
      >
        <span>📋 DS Khách</span>
        <span class="m-tab-badge">{{ conversations.length }}</span>
        <span v-if="handoffCount > 0" class="m-tab-urgent">{{ handoffCount }}</span>
      </button>

      <button
        type="button"
        class="mobile-inbox-tab"
        :class="{ active: mobilePane === 'chat' }"
        @click="mobilePane = 'chat'"
      >
        <span>💬 Chat</span>
        <span v-if="activeSession" class="m-tab-sub">
          {{ getShortCustomerName(activeSession.customer_name) }}
        </span>
      </button>

      <button
        type="button"
        class="mobile-inbox-tab"
        :class="{ active: mobilePane === 'insight' }"
        @click="openMobileInsightPane"
      >
        <span>📊 Insight KH</span>
        <span v-if="activeSession" class="m-tab-badge" :class="getTempChipClass(activeSession.buying_temperature)">
          {{ activeSession.buying_temperature ?? 50 }}°
        </span>
      </button>
    </nav>

    <!-- =================================================================== -->
    <!-- CỘT TRÁI: DANH SÁCH HỘI THOẠI KHÁCH HÀNG (& TAB TÀI LIỆU NGUỒN)      -->
    <!-- =================================================================== -->
    <aside class="inbox-left-panel">
      <!-- Top Mode Switcher: Hội thoại KH vs Tài liệu Nguồn -->
      <div class="left-panel-mode-tabs">
        <button
          class="left-mode-tab"
          :class="{ active: leftTab === 'conversations' }"
          @click="leftTab = 'conversations'"
        >
          <span>💬 Hội thoại KH</span>
          <span class="tab-pill-count">{{ conversations.length }}</span>
          <span v-if="handoffCount > 0" class="tab-alert-dot" :title="`${handoffCount} khách cần Sale hỗ trợ`">
            {{ handoffCount }}
          </span>
        </button>
        <button
          class="left-mode-tab"
          :class="{ active: leftTab === 'documents' }"
          @click="leftTab = 'documents'"
        >
          <span>📄 Tài liệu Page</span>
          <span class="tab-pill-count">{{ scopedDocCount }}</span>
        </button>
      </div>

      <!-- TAB 1: DANH SÁCH HỘI THOẠI KHÁCH HÀNG -->
      <div v-if="leftTab === 'conversations'" class="conversations-tab-body">
        <!-- Search & Add New Customer Conversation -->
        <div class="conv-search-bar-row">
          <div class="conv-search-input-wrap">
            <svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="conv-search-icon">
              <circle cx="11" cy="11" r="8"/>
              <line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <input
              v-model="searchQuery"
              type="text"
              class="conv-search-input"
              placeholder="Tìm tên KH, SĐT, tin nhắn..."
            />
            <button v-if="searchQuery" class="conv-search-clear" @click="searchQuery = ''">×</button>
          </div>
          <button
            class="btn-new-conv"
            title="Giả lập 1 khách hàng mới nhắn tin vào Fanpage"
            @click="showNewCustomerForm = !showNewCustomerForm"
          >
            + KH mới
          </button>
        </div>

        <!-- Inline Quick New Customer Form -->
        <div v-if="showNewCustomerForm" class="new-customer-quick-box">
          <div class="new-cust-header">
            <span>👤 Giả lập Khách hàng mới nhắn vào Page</span>
            <button class="btn-mini-close" @click="showNewCustomerForm = false">×</button>
          </div>
          <input
            v-model="newCustName"
            type="text"
            class="new-cust-input"
            placeholder="Tên hiển thị Facebook (VD: Anh Tuấn Vũ, Chị Bảo Ngọc)..."
          />
          <textarea
            v-model="newCustMessage"
            rows="2"
            class="new-cust-textarea"
            placeholder="Tin nhắn đầu tiên của khách (VD: Shop ơi tư vấn giúp anh gói trị mụn lưng với)..."
          ></textarea>
          <div class="new-cust-actions">
            <button
              class="btn btn-primary btn-xs"
              :disabled="isCreatingConv || !newCustMessage.trim()"
              @click="handleCreateNewCustomer"
            >
              {{ isCreatingConv ? '⏳ Đang gửi & AI đang trả lời...' : '🚀 Bắt đầu cuộc trò chuyện' }}
            </button>
          </div>
        </div>

        <!-- Filter Pills -->
        <div class="conv-filter-pills">
          <button
            v-for="f in filterOptions"
            :key="f.code"
            class="conv-filter-btn"
            :class="{ active: activeFilter === f.code, 'is-urgent': f.code === 'NEEDS_HANDOFF' && handoffCount > 0 }"
            @click="setFilter(f.code)"
          >
            <span>{{ f.label }}</span>
            <span v-if="f.count !== undefined" class="filter-mini-num">{{ f.count }}</span>
          </button>
        </div>

        <!-- Conversation List -->
        <div class="conv-list-scroll">
          <div v-if="isLoadingList && conversations.length === 0" class="conv-empty-state">
            ⏳ Đang tải danh sách hội thoại...
          </div>
          <div v-else-if="filteredConversations.length === 0" class="conv-empty-state">
            <div class="empty-emoji">💬</div>
            <div class="empty-title">Không có cuộc hội thoại nào khớp bộ lọc</div>
            <p class="empty-sub">Bấm <strong>"+ KH mới"</strong> ở trên để giả lập khách nhắn vào Fanpage.</p>
          </div>

          <div
            v-for="conv in filteredConversations"
            :key="conv.id"
            class="conv-item-card"
            :class="{
              active: selectedSessionId === conv.id,
              'needs-handoff': conv.needs_human_handoff || conv.status === 'NEEDS_HANDOFF',
              'is-human-mode': conv.control_mode === 'HUMAN_TAKEOVER'
            }"
            @click="selectConversation(conv.id, true)"
          >
            <!-- Avatar Column -->
            <div class="conv-avatar-wrap">
              <div class="conv-avatar" :class="getAvatarColorClass(conv)">
                {{ getInitials(conv.customer_name) }}
              </div>
              <span class="channel-mini-badge" title="Facebook Messenger">FB</span>
            </div>

            <!-- Content Column -->
            <div class="conv-card-main">
              <div class="conv-row-top">
                <div class="conv-name-group">
                  <span class="conv-cust-name">{{ conv.customer_name }}</span>
                  <span v-if="conv.honorific" class="conv-honorific-tag">{{ conv.honorific }}</span>
                </div>
                <span class="conv-time">{{ formatRelativeTime(conv.last_activity_at) }}</span>
              </div>

              <!-- Last message preview -->
              <div class="conv-preview-line">
                <span class="sender-prefix-tag" :class="getRoleClass(conv.last_sender_role)">
                  {{ getRoleShortLabel(conv.last_sender_role) }}:
                </span>
                <span class="preview-text">{{ conv.last_message_preview || 'Chưa có tin nhắn' }}</span>
              </div>

              <!-- Bottom Status & Insight Chips -->
              <div class="conv-badges-row">
                <!-- Control / Status Chip -->
                <span v-if="conv.needs_human_handoff || conv.status === 'NEEDS_HANDOFF'" class="conv-chip chip-urgent">
                  🚨 Cần Sale
                </span>
                <span v-else-if="conv.status === 'CONVERTED'" class="conv-chip chip-converted">
                  ✅ Đã chốt
                </span>
                <span v-else-if="conv.control_mode === 'HUMAN_TAKEOVER'" class="conv-chip chip-human">
                  👨‍💼 Sale trực
                </span>
                <span v-else class="conv-chip chip-bot">
                  🤖 AI trực
                </span>

                <!-- Buying Temperature Chip -->
                <span class="conv-chip" :class="getTempChipClass(conv.buying_temperature)">
                  {{ getTempEmoji(conv.buying_temperature) }} {{ conv.buying_temperature ?? 50 }}°
                </span>

                <!-- Captured Phone Chip -->
                <span v-if="conv.captured_phone" class="conv-chip chip-phone">
                  📞 {{ conv.captured_phone }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- TAB 2: KHO TÀI LIỆU NGUỒN CỦA PAGE (Nhúng trực tiếp ở cột trái khi chuyển tab) -->
      <div v-else class="documents-tab-body">
        <slot name="document-sidebar"></slot>
      </div>
    </aside>

    <!-- =================================================================== -->
    <!-- CỘT GIỮA: CHI TIẾT CUỘC TRÒ CHUYỆN KIỂU MESSENGER (KH - AI - SALE)  -->
    <!-- =================================================================== -->
    <section class="inbox-center-thread">
      <template v-if="activeSession">
        <!-- Messenger Top Header -->
        <header class="thread-header">
          <div class="thread-header-left">
            <button
              type="button"
              class="btn-mobile-back-list"
              title="Quay lại danh sách khách hàng"
              @click="mobilePane = 'list'"
            >
              ←
            </button>
            <div class="thread-avatar" :class="getAvatarColorClass(activeSession)">
              {{ getInitials(activeSession.customer_name) }}
            </div>
            <div class="thread-cust-meta">
              <div class="thread-name-row">
                <h3 class="thread-cust-name">{{ activeSession.customer_name }}</h3>
                <span class="thread-honorific-pill">Xưng hô: <strong>{{ activeSession.honorific || 'Chị' }}</strong></span>
                <span class="thread-stage-pill" :class="'stage-' + (activeSession.sales_stage || 'CONSULTING').toLowerCase()">
                  📍 {{ formatSalesStage(activeSession.sales_stage) }}
                </span>
              </div>
              <div class="thread-sub-row">
                <span class="thread-psid">ID: <code>{{ activeSession.sender_psid }}</code></span>
                <span>·</span>
                <span v-if="activeSession.control_mode === 'HUMAN_TAKEOVER'" class="mode-indicator-text is-human">
                  👨‍💼 Nhân viên Sale đang tiếp quản (AI đã tạm dừng trả lời tự động)
                </span>
                <span v-else class="mode-indicator-text is-bot">
                  🤖 AI LangGraph đang trực tự động (Phản hồi &lt; 2.5s)
                </span>
              </div>
            </div>
          </div>

          <!-- Human-in-the-Loop Control Buttons -->
          <div class="thread-header-actions">
            <!-- Toggle Takeover Button -->
            <button
              v-if="activeSession.control_mode !== 'HUMAN_TAKEOVER'"
              class="btn-control-mode btn-takeover"
              :disabled="isUpdatingControl"
              title="Tắt AI tự động ở cuộc hội thoại này để Nhân viên Sale trực tiếp chat với khách"
              @click="handleToggleControlMode('HUMAN_TAKEOVER')"
            >
              ✋ Sale Tiếp Nhận (Tắt AI)
            </button>
            <button
              v-else
              class="btn-control-mode btn-resume-ai"
              :disabled="isUpdatingControl"
              title="Giao lại cuộc hội thoại này cho AI Bot tự động trả lời"
              @click="handleToggleControlMode('BOT_ACTIVE')"
            >
              🤖 Bật lại AI Tự Động
            </button>

            <!-- Mark Converted Button -->
            <button
              v-if="activeSession.status !== 'CONVERTED'"
              class="btn-control-mode btn-mark-converted"
              :disabled="isUpdatingControl"
              title="Đánh dấu ca chat này đã chốt đơn/đặt lịch thành công (Dữ liệu vàng cho AI học ban đêm)"
              @click="handleMarkConverted"
            >
              ✅ Đánh dấu Đã Chốt
            </button>
            <span v-else class="converted-badge-header">
              🏆 Đã chốt thành công
            </span>

            <!-- Toggle Right Insight Panel -->
            <button
              class="btn-toggle-insight"
              :class="{ active: showInsightPanel }"
              title="Bật/Tắt cột Radar Insight Khách hàng bên phải"
              @click="handleToggleInsightButton"
            >
              📊 Insight KH
            </button>
          </div>
        </header>

        <!-- Urgent Human Handoff Banner -->
        <div v-if="activeSession.needs_human_handoff" class="handoff-alert-banner">
          <div class="handoff-alert-left">
            <span class="handoff-siren">🚨</span>
            <div>
              <div class="handoff-alert-title">
                AI đề xuất chuyển Nhân viên Sale hỗ trợ ngay!
                <span v-if="activeSession.captured_phone" class="handoff-phone-highlight">
                  📞 SĐT khách: {{ activeSession.captured_phone }}
                </span>
              </div>
              <div class="handoff-alert-reason">{{ activeSession.handoff_reason || 'Khách hàng cần nhân viên tư vấn chuyên sâu hoặc xác nhận lịch hẹn.' }}</div>
            </div>
          </div>
          <button
            class="btn-accept-handoff"
            @click="handleToggleControlMode('HUMAN_TAKEOVER')"
          >
            ✋ Tiếp nhận & Tắt AI ngay
          </button>
        </div>

        <!-- Messenger Multi-Actor Messages Stream -->
        <div ref="messagesScrollRef" class="thread-messages-stream">
          <div
            v-for="(msg, mIdx) in activeMessages"
            :key="msg.id || mIdx"
            class="msg-row"
            :class="{
              'msg-customer': msg.sender_role === 'CUSTOMER',
              'msg-ai-bot': msg.sender_role === 'AI_BOT',
              'msg-human-sale': msg.sender_role === 'HUMAN_SALE'
            }"
          >
            <!-- Customer Avatar on Left -->
            <div v-if="msg.sender_role === 'CUSTOMER'" class="msg-side-avatar cust-avatar">
              {{ getInitials(activeSession.customer_name) }}
            </div>

            <div class="msg-bubble-col">
              <!-- Role & Metadata Header above bubble -->
              <div class="msg-meta-header">
                <span v-if="msg.sender_role === 'CUSTOMER'" class="msg-actor-badge badge-cust">
                  👤 {{ msg.sender_name || activeSession.customer_name }} (Khách hàng)
                </span>
                <span v-else-if="msg.sender_role === 'AI_BOT'" class="msg-actor-badge badge-ai">
                  🤖 {{ msg.sender_name || 'AI Trợ Lý Fanpage' }}
                  <span v-if="msg.latency_ms" class="msg-latency-tag">⚡ {{ msg.latency_ms }}ms</span>
                  <span v-if="msg.sales_stage" class="msg-stage-tag">{{ formatSalesStage(msg.sales_stage) }}</span>
                </span>
                <span v-else class="msg-actor-badge badge-sale">
                  👨‍💼 {{ msg.sender_name || 'Nhân viên Sale' }} (Người thật)
                  <span class="sale-gold-tag">⭐ Mẫu thực chiến</span>
                </span>
                <span class="msg-time-label">{{ formatTimeOnly(msg.created_at) }}</span>
              </div>

              <!-- Message Bubble Content -->
              <div class="msg-bubble-box">
                <p
                  v-for="(para, pIdx) in splitParagraphs(msg.content)"
                  :key="pIdx"
                  class="msg-para"
                >
                  {{ para }}
                </p>
              </div>

              <!-- AI Bot Telemetry & Action Bar (Teach AI + Harness² Optimization) -->
              <div v-if="msg.sender_role === 'AI_BOT'" class="ai-msg-footer-bar">
                <div v-if="msg.triggered_hooks && msg.triggered_hooks.length > 0" class="ai-hooks-row">
                  <span
                    v-for="h in msg.triggered_hooks"
                    :key="h"
                    class="mini-hook-chip"
                    title="Chốt chặn Guardrail In-Loop đã kiểm duyệt"
                  >
                    🛡️ {{ h }}
                  </span>
                </div>

                <div class="ai-action-buttons">
                  <button
                    class="btn-ai-msg-action btn-teach-inline"
                    title="Báo sai câu trả lời này và dạy lại quy tắc cho AI"
                    @click="handleTeachAiOnMessage(msg, mIdx)"
                  >
                    💬 Báo sai & Dạy AI
                  </button>
                  <button
                    class="btn-ai-msg-action btn-harness-inline"
                    :disabled="optimizingMsgId === msg.id"
                    title="Chạy vòng lặp Propose–Probe–Select (Harness²) để tự tối ưu câu trả lời này"
                    @click="handleOptimizeMessageHarness(msg, mIdx)"
                  >
                    {{ optimizingMsgId === msg.id ? '⏳ Đang tối ưu Harness²...' : '🧬 Tự tối ưu Harness²' }}
                  </button>
                </div>
              </div>
            </div>

            <!-- Right-side Avatar for AI Bot or Human Sale -->
            <div v-if="msg.sender_role === 'AI_BOT'" class="msg-side-avatar ai-avatar" title="AI Bot">
              🤖
            </div>
            <div v-else-if="msg.sender_role === 'HUMAN_SALE'" class="msg-side-avatar sale-avatar" title="Nhân viên Sale">
              👨‍💼
            </div>
          </div>

          <!-- Typing Indicator when sending -->
          <div v-if="isSendingMessage" class="msg-row msg-ai-bot">
            <div class="msg-bubble-col">
              <div class="msg-bubble-box typing-bubble">
                <span>⏳ Đang xử lý tin nhắn...</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Dual-Mode Messenger Composer Footer -->
        <footer class="thread-composer-footer">
          <div class="composer-mode-bar">
            <div class="composer-mode-tabs">
              <button
                class="composer-mode-btn mode-sale"
                :class="{ active: composerMode === 'HUMAN_SALE' }"
                @click="composerMode = 'HUMAN_SALE'"
              >
                👨‍💼 Trả lời khách (Tư cách Sale thật)
              </button>
              <button
                class="composer-mode-btn mode-customer"
                :class="{ active: composerMode === 'CUSTOMER' }"
                @click="composerMode = 'CUSTOMER'"
              >
                👤 Giả lập Khách nhắn tiếp vào hộp thư
              </button>
            </div>

            <span class="composer-mode-hint">
              <template v-if="composerMode === 'HUMAN_SALE'">
                💡 Khi gửi, hệ thống tự động chuyển sang <strong>Sale trực (Tắt AI)</strong> và lưu câu văn của bạn làm mẫu huấn luyện AI.
              </template>
              <template v-else>
                💡 Giả lập khách nhắn qua Messenger:
                <strong v-if="activeSession.control_mode === 'HUMAN_TAKEOVER'">AI đang tắt — Chờ Sale trả lời</strong>
                <strong v-else>AI đang bật — Bot sẽ tự động trả lời ngay</strong>
              </template>
            </span>
          </div>

          <div class="composer-input-row">
            <textarea
              v-model="composerText"
              rows="2"
              class="composer-textarea"
              :class="composerMode === 'HUMAN_SALE' ? 'is-sale-input' : 'is-cust-input'"
              :placeholder="
                composerMode === 'HUMAN_SALE'
                  ? `Nhập tin nhắn tư vấn / chốt đơn gửi cho ${activeSession.customer_name} (Tư cách Nhân viên Sale)...`
                  : `Giả lập ${activeSession.customer_name} nhắn tin mới vào Fanpage...`
              "
              @keydown.enter.exact.prevent="handleSendComposerMessage(false)"
            ></textarea>

            <div class="composer-send-btns">
              <button
                class="btn-send-main"
                :class="composerMode === 'HUMAN_SALE' ? 'btn-send-sale' : 'btn-send-cust'"
                :disabled="isSendingMessage || !composerText.trim()"
                @click="handleSendComposerMessage(false)"
              >
                <span v-if="composerMode === 'HUMAN_SALE'">📤 Gửi (Sale)</span>
                <span v-else>💬 Khách gửi</span>
              </button>

              <button
                v-if="composerMode === 'HUMAN_SALE'"
                class="btn-send-convert"
                :disabled="isSendingMessage || !composerText.trim()"
                title="Gửi tin nhắn này và đánh dấu Đã chốt đơn thành công (Phục vụ AI học ban đêm)"
                @click="handleSendComposerMessage(true)"
              >
                ✅ Gửi & Chốt đơn
              </button>
            </div>
          </div>
        </footer>
      </template>

      <div v-else class="thread-empty-placeholder">
        <div class="placeholder-icon">💬</div>
        <h3>Chọn một cuộc hội thoại ở cột bên trái</h3>
        <p>Xem toàn bộ lịch sử chat giữa Khách hàng, AI Bot và Nhân viên Sale theo thời gian thực.</p>
      </div>
    </section>

    <!-- =================================================================== -->
    <!-- CỘT PHẢI: RADAR THẤU HIỂU INSIGHT KHÁCH HÀNG & ĐÀO TẠO COLD PATH     -->
    <!-- =================================================================== -->
    <aside v-if="(showInsightPanel || mobilePane === 'insight') && activeSession" class="inbox-right-insight">
      <div class="insight-panel-header">
        <div class="insight-header-title">
          <button
            type="button"
            class="btn-mobile-back-chat"
            title="Quay lại khung chat"
            @click="mobilePane = 'chat'"
          >
            ← Chat
          </button>
          <span>🎯 Insight & Hồ Sơ Khách Hàng</span>
        </div>
        <span class="insight-live-badge">Real-time AI</span>
      </div>

      <div class="insight-panel-scroll">
        <!-- 1. Buying Temperature Thermometer Card -->
        <div class="insight-card temp-card">
          <div class="insight-card-top">
            <span class="insight-card-label">🌡️ Nhiệt độ Mua hàng</span>
            <div class="temp-score-group">
              <span class="temp-big-num" :class="getTempTextClass(activeSession.buying_temperature)">
                {{ activeSession.buying_temperature ?? 50 }}°
              </span>
              <span
                v-if="activeInsight.temperature_delta"
                class="temp-delta-pill"
                :class="activeInsight.temperature_delta > 0 ? 'delta-up' : 'delta-down'"
              >
                {{ activeInsight.temperature_delta > 0 ? '+' : '' }}{{ activeInsight.temperature_delta }}°
              </span>
              <span class="temp-status-tag" :class="getTempChipClass(activeSession.buying_temperature)">
                {{ activeSession.temperature_label || 'WARM' }}
              </span>
            </div>
          </div>

          <div class="temp-progress-track">
            <div
              class="temp-progress-fill"
              :class="getTempBarClass(activeSession.buying_temperature)"
              :style="{ width: Math.min(100, Math.max(8, activeSession.buying_temperature || 50)) + '%' }"
            ></div>
          </div>

          <div class="temp-scale-labels">
            <span>❄️ Lạnh (&lt;45°)</span>
            <span>🌤️ Ấm (45-74°)</span>
            <span>🔥 Nóng (75-100°)</span>
          </div>
        </div>

        <!-- 2. Captured Lead & Contact Info Card -->
        <div class="insight-card lead-crm-card">
          <div class="insight-card-label">📇 Thông tin Lead & Liên hệ</div>
          <div class="lead-info-list">
            <div class="lead-info-row">
              <span class="lead-key">Số điện thoại:</span>
              <span v-if="activeSession.captured_phone" class="lead-val phone-found">
                📞 {{ activeSession.captured_phone }}
              </span>
              <span v-else class="lead-val phone-missing">Chưa cung cấp SĐT</span>
            </div>
            <div class="lead-info-row">
              <span class="lead-key">Xưng hô chuẩn:</span>
              <span class="lead-val"><strong>{{ activeSession.honorific || 'Chị' }}</strong></span>
            </div>
            <div class="lead-info-row">
              <span class="lead-key">Chủ đề quan tâm:</span>
              <span class="lead-val topic-highlight">
                {{ activeInsight.active_topic || 'Tư vấn dịch vụ chung' }}
              </span>
            </div>
            <div class="lead-info-row">
              <span class="lead-key">Giai đoạn phễu:</span>
              <span class="lead-val">
                <strong>{{ formatSalesStage(activeSession.sales_stage) }}</strong>
              </span>
            </div>
          </div>
        </div>

        <!-- 3. Buyer Archetype & Recommended Strategy -->
        <div class="insight-card archetype-card">
          <div class="insight-card-label">🧠 Chân dung Tâm lý (Archetype)</div>
          <div class="archetype-badge-box">
            {{ activeInsight.archetype_label || activeSession.buyer_archetype || 'Đang tìm hiểu dịch vụ' }}
          </div>

          <div v-if="activeInsight.recommended_strategy" class="strategy-recommend-box">
            <div class="strategy-title">💡 Chiến thuật Gợi ý cho Sale / AI:</div>
            <p class="strategy-text">{{ activeInsight.recommended_strategy }}</p>
          </div>
        </div>

        <!-- 4. Extracted Pains & Hidden Concerns -->
        <div class="insight-card pains-card">
          <div class="insight-card-label">🩺 Nỗi đau & Vấn đề của Khách</div>
          <div v-if="extractedPainsList.length > 0" class="insight-tags-wrap">
            <span
              v-for="(pain, idx) in extractedPainsList"
              :key="'p-' + idx"
              class="insight-pain-chip"
            >
              🔸 {{ pain }}
            </span>
          </div>
          <p v-else class="insight-empty-note">Chưa bóc tách được vấn đề cụ thể.</p>

          <div class="insight-card-label mt-10">🛡️ Rào cản / Nỗi sợ ngầm</div>
          <div v-if="hiddenConcernsList.length > 0" class="insight-tags-wrap">
            <span
              v-for="(concern, idx) in hiddenConcernsList"
              :key="'c-' + idx"
              class="insight-concern-chip"
            >
              ⚠️ {{ concern }}
            </span>
          </div>
          <p v-else class="insight-empty-note">Chưa phát hiện rào cản tâm lý.</p>
        </div>

        <!-- 5. Cold Path Nightly Extraction Trigger (Học từ ca chat của Sale thật) -->
        <div class="insight-card coldpath-training-card">
          <div class="coldpath-header">
            <span class="coldpath-icon">🌙</span>
            <div>
              <div class="coldpath-title">Đào Tạo AI Từ Ca Chat Này</div>
              <div class="coldpath-sub">Luồng 2 (Cold Path): Rút trích văn mẫu & Kiểm định bảo mật</div>
            </div>
          </div>
          <p class="coldpath-desc">
            Nếu ca hội thoại này có cách xử lý khéo léo của <strong>Sale thật</strong>, hãy bấm trích xuất để AI học lỏm và đưa vào <strong>Hàng đợi Chờ duyệt (Staging Queue)</strong>.
          </p>
          <button
            class="btn-extract-coldpath"
            :disabled="isExtractingRule"
            @click="handleExtractRuleFromSession"
          >
            {{ isExtractingRule ? '⏳ Đang phân tích & kiểm định bảo mật...' : '✨ Trích xuất Ca Chat Này Cho AI Học' }}
          </button>

          <!-- Result preview if just extracted -->
          <div v-if="lastExtractionResult" class="extraction-result-box">
            <div class="ext-res-title">
              ✅ Đã trích xuất {{ lastExtractionResult.staged_count || 1 }} mẫu vào Hàng đợi Duyệt!
            </div>
            <button class="btn-open-staging-link" @click="$emit('open-playbook')">
              👉 Mở Bộ nhớ AI (Tab Chờ duyệt) để duyệt ngay
            </button>
          </div>
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import { api } from '../api'

const props = defineProps({
  activePage: {
    type: Object,
    default: null
  },
  activePageId: {
    type: String,
    default: null
  },
  scopedDocCount: {
    type: Number,
    default: 0
  },
  isConnected: {
    type: Boolean,
    default: true
  }
})

const emit = defineEmits([
  'teach-ai',
  'open-playbook',
  'toast'
])

const leftTab = ref('conversations')
const mobilePane = ref('list') // 'list' | 'chat' | 'insight'
const activeFilter = ref('ALL')
const searchQuery = ref('')
const conversations = ref([])
const selectedSessionId = ref(null)
const activeSession = ref(null)
const activeMessages = ref([])
const isLoadingList = ref(false)
const isSendingMessage = ref(false)
const isUpdatingControl = ref(false)
const isExtractingRule = ref(false)
const lastExtractionResult = ref(null)
const optimizingMsgId = ref(null)
const showInsightPanel = ref(true)

const openMobileInsightPane = () => {
  showInsightPanel.value = true
  mobilePane.value = 'insight'
}

const handleToggleInsightButton = () => {
  if (typeof window !== 'undefined' && window.innerWidth <= 960) {
    showInsightPanel.value = true
    mobilePane.value = mobilePane.value === 'insight' ? 'chat' : 'insight'
  } else {
    showInsightPanel.value = !showInsightPanel.value
  }
}

const getShortCustomerName = (fullName) => {
  if (!fullName) return ''
  const parts = String(fullName).trim().split(/\s+/)
  return parts.length > 2 ? parts.slice(-2).join(' ') : fullName
}

// Composer state
const composerMode = ref('HUMAN_SALE') // 'HUMAN_SALE' | 'CUSTOMER'
const composerText = ref('')
const messagesScrollRef = ref(null)

// New customer simulation form
const showNewCustomerForm = ref(false)
const newCustName = ref('')
const newCustMessage = ref('')
const isCreatingConv = ref(false)

const handoffCount = computed(() => {
  return conversations.value.filter(c => c.needs_human_handoff || c.status === 'NEEDS_HANDOFF').length
})

const phoneCount = computed(() => {
  return conversations.value.filter(c => Boolean(c.captured_phone)).length
})

const filterOptions = computed(() => [
  { code: 'ALL', label: 'Tất cả', count: conversations.value.length },
  { code: 'NEEDS_HANDOFF', label: '🚨 Cần Sale', count: handoffCount.value },
  { code: 'HAS_PHONE', label: '📞 Có SĐT', count: phoneCount.value },
  { code: 'BOT_ACTIVE', label: '🤖 AI trực' },
  { code: 'HUMAN_TAKEOVER', label: '👨‍💼 Sale trực' }
])

const filteredConversations = computed(() => {
  return conversations.value.filter(c => {
    if (activeFilter.value === 'NEEDS_HANDOFF' && !(c.needs_human_handoff || c.status === 'NEEDS_HANDOFF')) {
      return false
    }
    if (activeFilter.value === 'HAS_PHONE' && !c.captured_phone) {
      return false
    }
    if (activeFilter.value === 'BOT_ACTIVE' && c.control_mode !== 'BOT_ACTIVE') {
      return false
    }
    if (activeFilter.value === 'HUMAN_TAKEOVER' && c.control_mode !== 'HUMAN_TAKEOVER') {
      return false
    }
    if (searchQuery.value.trim()) {
      const q = searchQuery.value.trim().toLowerCase()
      const hay = `${c.customer_name || ''} ${c.captured_phone || ''} ${c.last_message_preview || ''} ${c.buyer_archetype || ''}`.toLowerCase()
      if (!hay.includes(q)) return false
    }
    return true
  })
})

const activeInsight = computed(() => {
  return (activeSession.value && activeSession.value.customer_insight) || {}
})

const extractedPainsList = computed(() => {
  const arr = activeInsight.value.extracted_pains
  return Array.isArray(arr) ? arr : []
})

const hiddenConcernsList = computed(() => {
  const arr = activeInsight.value.hidden_concerns
  return Array.isArray(arr) ? arr : []
})

const scrollToBottom = async () => {
  await nextTick()
  if (messagesScrollRef.value) {
    messagesScrollRef.value.scrollTop = messagesScrollRef.value.scrollHeight
  }
}

const loadConversations = async (preserveSelection = true) => {
  isLoadingList.value = true
  try {
    const list = await api.getConversations(props.activePageId, 'ALL')
    conversations.value = Array.isArray(list) ? list : []
    if (conversations.value.length > 0) {
      const exists = preserveSelection && selectedSessionId.value && conversations.value.some(c => c.id === selectedSessionId.value)
      const targetId = exists ? selectedSessionId.value : conversations.value[0].id
      await selectConversation(targetId)
    } else {
      selectedSessionId.value = null
      activeSession.value = null
      activeMessages.value = []
    }
  } catch (err) {
    console.error('Load conversations error:', err)
    conversations.value = []
  } finally {
    isLoadingList.value = false
  }
}

const selectConversation = async (sessionId, switchToChatOnMobile = false) => {
  selectedSessionId.value = sessionId
  lastExtractionResult.value = null
  if (switchToChatOnMobile) {
    mobilePane.value = 'chat'
  }
  try {
    const detail = await api.getConversationDetail(sessionId)
    if (detail && detail.session) {
      activeSession.value = detail.session
      activeMessages.value = Array.isArray(detail.messages) ? detail.messages : []
      // Update list item unread count
      const idx = conversations.value.findIndex(c => c.id === sessionId)
      if (idx !== -1) {
        conversations.value[idx] = { ...conversations.value[idx], ...detail.session, unread_count: 0 }
      }
      await scrollToBottom()
    }
  } catch (err) {
    console.error('Load conversation detail error:', err)
  }
}

const setFilter = (code) => {
  activeFilter.value = code
}

const handleCreateNewCustomer = async () => {
  if (!newCustMessage.value.trim()) return
  isCreatingConv.value = true
  try {
    const custName = newCustName.value.trim() || 'Khách hàng Messenger'
    const firstMsg = newCustMessage.value.trim()
    const res = await api.createNewConversation({
      pageId: props.activePageId,
      customerName: custName,
      message: firstMsg
    })
    if (res && res.session) {
      await loadConversations(false)
      await selectConversation(res.session.id, true)
    }
    newCustName.value = ''
    newCustMessage.value = ''
    showNewCustomerForm.value = false
    emit('toast', { message: `💬 Đã tạo cuộc trò chuyện mới với "${custName}"`, type: 'success' })
  } catch (err) {
    emit('toast', { message: `Lỗi tạo cuộc trò chuyện: ${err.message}`, type: 'error' })
  } finally {
    isCreatingConv.value = false
  }
}

const handleSendComposerMessage = async (markConverted = false) => {
  if (!composerText.value.trim() || !activeSession.value) return
  const text = composerText.value.trim()
  composerText.value = ''
  isSendingMessage.value = true

  try {
    if (composerMode.value === 'HUMAN_SALE') {
      const res = await api.sendHumanSaleReplyInConversation(activeSession.value.id, {
        message: text,
        markConverted
      })
      if (res && res.session) {
        activeSession.value = res.session
        activeMessages.value = res.messages || []
      }
      emit('toast', {
        message: markConverted
          ? '✅ Đã gửi phản hồi Sale & Đánh dấu Chốt đơn thành công!'
          : '👨‍💼 Đã gửi tin nhắn tư cách Nhân viên Sale (AI đã tạm dừng ở khách này)',
        type: 'success'
      })
    } else {
      // Simulating customer sending a follow-up message in Messenger
      const res = await api.sendCustomerTurnInConversation(activeSession.value.id, {
        message: text,
        customerName: activeSession.value.customer_name
      })
      if (res && res.session) {
        activeSession.value = res.session
        activeMessages.value = res.messages || []
      }
      if (res && res.bot_paused_by_human_takeover) {
        emit('toast', {
          message: '👤 Khách vừa nhắn tin mới. Vì Sale đang tiếp nhận nên AI không tự động chen ngang.',
          type: 'info'
        })
      }
    }
    // Sync list item
    const idx = conversations.value.findIndex(c => c.id === activeSession.value.id)
    if (idx !== -1) {
      conversations.value[idx] = { ...conversations.value[idx], ...activeSession.value }
    }
    await scrollToBottom()
  } catch (err) {
    emit('toast', { message: `Lỗi gửi tin nhắn: ${err.message}`, type: 'error' })
  } finally {
    isSendingMessage.value = false
  }
}

const handleToggleControlMode = async (targetMode) => {
  if (!activeSession.value) return
  isUpdatingControl.value = true
  try {
    const newStatus = targetMode === 'HUMAN_TAKEOVER' ? 'HUMAN_TAKEOVER' : 'BOT_ACTIVE'
    const updated = await api.updateConversationControlMode(activeSession.value.id, {
      controlMode: targetMode,
      status: newStatus
    })
    activeSession.value = { ...activeSession.value, ...updated }
    if (targetMode === 'HUMAN_TAKEOVER') {
      composerMode.value = 'HUMAN_SALE'
    }
    const idx = conversations.value.findIndex(c => c.id === activeSession.value.id)
    if (idx !== -1) {
      conversations.value[idx] = { ...conversations.value[idx], ...activeSession.value }
    }
    emit('toast', {
      message: targetMode === 'HUMAN_TAKEOVER'
        ? `✋ Nhân viên Sale đã tiếp nhận "${activeSession.value.customer_name}". AI đã tạm dừng.`
        : `🤖 Đã bật lại AI tự động trực cho "${activeSession.value.customer_name}".`,
      type: 'success'
    })
  } catch (err) {
    emit('toast', { message: `Lỗi chuyển chế độ trực: ${err.message}`, type: 'error' })
  } finally {
    isUpdatingControl.value = false
  }
}

const handleMarkConverted = async () => {
  if (!activeSession.value) return
  isUpdatingControl.value = true
  try {
    const updated = await api.updateConversationControlMode(activeSession.value.id, {
      controlMode: activeSession.value.control_mode,
      status: 'CONVERTED'
    })
    activeSession.value = { ...activeSession.value, ...updated }
    const idx = conversations.value.findIndex(c => c.id === activeSession.value.id)
    if (idx !== -1) {
      conversations.value[idx] = { ...conversations.value[idx], ...activeSession.value }
    }
    emit('toast', {
      message: `🏆 Đã đánh dấu chốt đơn thành công cho "${activeSession.value.customer_name}"!`,
      type: 'success'
    })
  } catch (err) {
    emit('toast', { message: `Lỗi cập nhật trạng thái: ${err.message}`, type: 'error' })
  } finally {
    isUpdatingControl.value = false
  }
}

const handleExtractRuleFromSession = async () => {
  if (!activeSession.value) return
  isExtractingRule.value = true
  lastExtractionResult.value = null
  try {
    const res = await api.extractRuleFromConversation(activeSession.value.id)
    lastExtractionResult.value = res
    emit('toast', {
      message: '🌙 Đã trích xuất bài học từ ca chat vào Hàng đợi Chờ duyệt (Staging Queue)!',
      type: 'success'
    })
  } catch (err) {
    emit('toast', { message: `Lỗi trích xuất Cold Path: ${err.message}`, type: 'error' })
  } finally {
    isExtractingRule.value = false
  }
}

const handleTeachAiOnMessage = (msg, mIdx) => {
  // Find preceding customer message
  let prevCustomerQuestion = 'Khách hàng hỏi tư vấn trên Fanpage'
  for (let i = mIdx - 1; i >= 0; i--) {
    if (activeMessages.value[i].sender_role === 'CUSTOMER') {
      prevCustomerQuestion = activeMessages.value[i].content
      break
    }
  }
  emit('teach-ai', {
    question: prevCustomerQuestion,
    faultyAnswer: msg.content,
    documentIds: [],
    messageIndex: -1,
    appliedRuleIds: []
  })
}

const handleOptimizeMessageHarness = async (msg, mIdx) => {
  let prevCustomerQuestion = 'Tư vấn dịch vụ Fanpage'
  for (let i = mIdx - 1; i >= 0; i--) {
    if (activeMessages.value[i].sender_role === 'CUSTOMER') {
      prevCustomerQuestion = activeMessages.value[i].content
      break
    }
  }
  optimizingMsgId.value = msg.id
  try {
    const res = await api.optimizeHarness({
      pageId: props.activePageId,
      customerMessage: prevCustomerQuestion,
      baseResponse: msg.content,
      triggerReason: 'INBOX_1CLICK'
    })
    if (res && res.verdict === 'ADOPTED' && res.improved_reply) {
      msg.content = res.improved_reply
      emit('toast', {
        message: '🧬 Harness² đã nâng cấp câu trả lời và lưu quy tắc mới vào Bộ nhớ AI!',
        type: 'success'
      })
    } else {
      emit('toast', {
        message: '🧬 Harness² đánh giá câu trả lời hiện tại đã đạt chuẩn tối ưu (KEEP_BASE).',
        type: 'info'
      })
    }
  } catch (err) {
    emit('toast', { message: `Lỗi chạy Harness²: ${err.message}`, type: 'error' })
  } finally {
    optimizingMsgId.value = null
  }
}

// Formatting Helpers
const splitParagraphs = (text) => {
  if (!text) return ['']
  return String(text).split(/\n{2,}/).map(s => s.trim()).filter(Boolean)
}

const getInitials = (name) => {
  if (!name) return 'KH'
  const parts = name.trim().split(/\s+/)
  if (parts.length >= 2) {
    return (parts[parts.length - 2][0] + parts[parts.length - 1][0]).toUpperCase()
  }
  return name.slice(0, 2).toUpperCase()
}

const getAvatarColorClass = (conv) => {
  if (conv?.needs_human_handoff || conv?.status === 'NEEDS_HANDOFF') return 'av-urgent'
  if (conv?.status === 'CONVERTED') return 'av-converted'
  if (conv?.control_mode === 'HUMAN_TAKEOVER') return 'av-human'
  return 'av-bot'
}

const getRoleShortLabel = (role) => {
  if (role === 'HUMAN_SALE') return '👨‍💼 Sale'
  if (role === 'AI_BOT') return '🤖 AI'
  return '👤 Khách'
}

const getRoleClass = (role) => {
  if (role === 'HUMAN_SALE') return 'role-sale'
  if (role === 'AI_BOT') return 'role-ai'
  return 'role-cust'
}

const getTempEmoji = (temp) => {
  const t = Number(temp) || 50
  if (t >= 75) return '🔥'
  if (t >= 45) return '🌤️'
  return '❄️'
}

const getTempChipClass = (temp) => {
  const t = Number(temp) || 50
  if (t >= 75) return 'temp-hot'
  if (t >= 45) return 'temp-warm'
  return 'temp-cold'
}

const getTempTextClass = (temp) => {
  const t = Number(temp) || 50
  if (t >= 75) return 'text-hot'
  if (t >= 45) return 'text-warm'
  return 'text-cold'
}

const getTempBarClass = (temp) => {
  const t = Number(temp) || 50
  if (t >= 75) return 'bar-hot'
  if (t >= 45) return 'bar-warm'
  return 'bar-cold'
}

const formatSalesStage = (stage) => {
  const map = {
    CONSULTING: 'Tư vấn & Khám phá',
    DISCOVERY: 'Khảo sát nhu cầu',
    VALUE_CONSULTATION: 'Tư vấn Giá trị',
    OBJECTION_PRICE: 'Xử lý Chê đắt / Giá',
    CLOSING: 'Chốt SĐT & Lịch hẹn',
    SOFT_BOOKING_CLOSE: 'Chốt Lịch hẹn',
    SUPPORT: 'Chăm sóc & Bảo hành',
    HUMAN_HANDOFF: 'Chuyển Nhân viên Sale'
  }
  return map[stage] || stage || 'Tư vấn'
}

const formatRelativeTime = (iso) => {
  if (!iso) return 'Vừa xong'
  try {
    const diffSec = Math.max(1, Math.floor((Date.now() - new Date(iso).getTime()) / 1000))
    if (diffSec < 60) return 'Vừa xong'
    if (diffSec < 3600) return `${Math.floor(diffSec / 60)} phút`
    if (diffSec < 86400) return `${Math.floor(diffSec / 3600)} giờ`
    return `${Math.floor(diffSec / 86400)} ngày`
  } catch {
    return 'Vừa xong'
  }
}

const formatTimeOnly = (iso) => {
  if (!iso) return ''
  try {
    return new Date(iso).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })
  } catch {
    return ''
  }
}

watch(() => props.activePageId, () => {
  loadConversations(false)
})

watch(() => props.isConnected, () => {
  loadConversations(true)
})

onMounted(() => {
  loadConversations(false)
})

defineExpose({
  refreshInbox: () => loadConversations(true)
})
</script>

<style scoped>
.omnichannel-inbox-layout {
  display: flex;
  flex: 1;
  width: 100%;
  height: 100%;
  gap: 14px;
  overflow: hidden;
}

/* ========================================================================= */
/* LEFT PANEL: CONVERSATION LIST & DOCUMENT SOURCE TAB                       */
/* ========================================================================= */
.inbox-left-panel {
  width: 340px;
  min-width: 310px;
  max-width: 360px;
  background: #ffffff;
  border: 1px solid #dde3ea;
  border-radius: 22px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.03);
}

.left-panel-mode-tabs {
  display: flex;
  padding: 8px;
  gap: 6px;
  background: #f8fafd;
  border-bottom: 1px solid #e2e8f0;
}

.left-mode-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 12px;
  border: 1px solid transparent;
  background: transparent;
  color: #475569;
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s ease;
}

.left-mode-tab:hover {
  background: #eef2f6;
}

.left-mode-tab.active {
  background: #ffffff;
  color: #0b57d0;
  border-color: #d3e3fd;
  box-shadow: 0 1px 4px rgba(11, 87, 208, 0.08);
}

.tab-pill-count {
  background: #e2e8f0;
  color: #334155;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 999px;
}

.left-mode-tab.active .tab-pill-count {
  background: #d3e3fd;
  color: #0b57d0;
}

.tab-alert-dot {
  background: #dc2626;
  color: #ffffff;
  font-size: 10.5px;
  font-weight: 800;
  padding: 1px 6px;
  border-radius: 999px;
  animation: pulseRed 1.8s infinite;
}

@keyframes pulseRed {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.08); }
}

.conversations-tab-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
}

.documents-tab-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
}

.documents-tab-body :deep(.sidebar) {
  width: 100% !important;
  max-width: 100% !important;
  border: none !important;
  border-radius: 0 !important;
  box-shadow: none !important;
}

.conv-search-bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px 8px;
}

.conv-search-input-wrap {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
}

.conv-search-icon {
  position: absolute;
  left: 10px;
  color: #64748b;
}

.conv-search-input {
  width: 100%;
  padding: 7px 26px 7px 30px;
  border: 1px solid #cbd5e1;
  border-radius: 999px;
  font-size: 12.5px;
  background: #f8fafc;
  color: #1e293b;
  outline: none;
}

.conv-search-input:focus {
  border-color: #0b57d0;
  background: #ffffff;
}

.conv-search-clear {
  position: absolute;
  right: 8px;
  border: none;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  font-size: 14px;
}

.btn-new-conv {
  padding: 7px 11px;
  border-radius: 999px;
  border: 1px solid #0b57d0;
  background: #e8f0fe;
  color: #0b57d0;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
}

.btn-new-conv:hover {
  background: #d3e3fd;
}

.new-customer-quick-box {
  margin: 0 12px 10px;
  padding: 10px;
  background: #f0f4f9;
  border: 1px solid #c2d7f8;
  border-radius: 14px;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.new-cust-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  font-weight: 700;
  color: #0b57d0;
}

.btn-mini-close {
  border: none;
  background: transparent;
  font-size: 16px;
  cursor: pointer;
  color: #475569;
}

.new-cust-input,
.new-cust-textarea {
  width: 100%;
  padding: 7px 9px;
  border-radius: 8px;
  border: 1px solid #cbd5e1;
  font-size: 12px;
  background: #ffffff;
  color: #0f172a;
}

.new-cust-actions {
  display: flex;
  justify-content: flex-end;
}

.conv-filter-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  padding: 0 12px 10px;
  border-bottom: 1px solid #eef2f6;
}

.conv-filter-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 9px;
  border-radius: 999px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  color: #475569;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}

.conv-filter-btn.active {
  background: #0b57d0;
  color: #ffffff;
  border-color: #0b57d0;
}

.conv-filter-btn.is-urgent:not(.active) {
  border-color: #fca5a5;
  background: #fef2f2;
  color: #b91c1c;
}

.filter-mini-num {
  font-size: 10.5px;
  opacity: 0.9;
}

.conv-list-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.conv-item-card {
  display: flex;
  gap: 10px;
  padding: 11px;
  border-radius: 16px;
  border: 1px solid transparent;
  background: #ffffff;
  cursor: pointer;
  transition: all 0.15s ease;
}

.conv-item-card:hover {
  background: #f8fafd;
  border-color: #e2e8f0;
}

.conv-item-card.active {
  background: #e8f0fe;
  border-color: #a8c7fa;
}

.conv-item-card.needs-handoff:not(.active) {
  background: #fffbeb;
  border-color: #fde68a;
}

.conv-avatar-wrap {
  position: relative;
  flex-shrink: 0;
}

.conv-avatar {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: 13.5px;
  color: #ffffff;
}

.av-bot { background: linear-gradient(135deg, #1a73e8, #0b57d0); }
.av-urgent { background: linear-gradient(135deg, #ea580c, #dc2626); }
.av-human { background: linear-gradient(135deg, #059669, #047857); }
.av-converted { background: linear-gradient(135deg, #0d9488, #0f766e); }

.channel-mini-badge {
  position: absolute;
  bottom: -2px;
  right: -2px;
  background: #1877f2;
  color: #ffffff;
  font-size: 9px;
  font-weight: 800;
  padding: 1px 4px;
  border-radius: 6px;
  border: 1.5px solid #ffffff;
}

.conv-card-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.conv-row-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
}

.conv-name-group {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}

.conv-cust-name {
  font-size: 13.5px;
  font-weight: 700;
  color: #0f172a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-honorific-tag {
  font-size: 10.5px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #475569;
}

.conv-time {
  font-size: 11px;
  color: #64748b;
  flex-shrink: 0;
}

.conv-preview-line {
  font-size: 12px;
  color: #475569;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sender-prefix-tag {
  font-weight: 700;
  margin-right: 3px;
}

.role-sale { color: #047857; }
.role-ai { color: #0b57d0; }
.role-cust { color: #334155; }

.conv-badges-row {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 2px;
}

.conv-chip {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
}

.chip-urgent { background: #fee2e2; color: #b91c1c; }
.chip-converted { background: #d1fae5; color: #065f46; }
.chip-human { background: #ecfdf5; color: #047857; border: 1px solid #a7f3d0; }
.chip-bot { background: #e8f0fe; color: #0b57d0; }
.chip-phone { background: #fef3c7; color: #92400e; }

.temp-hot { background: #ffedd5; color: #c2410c; }
.temp-warm { background: #fef9c3; color: #854d0e; }
.temp-cold { background: #e0f2fe; color: #0369a1; }

/* ========================================================================= */
/* CENTER COLUMN: MESSENGER CHAT THREAD                                      */
/* ========================================================================= */
.inbox-center-thread {
  flex: 1;
  min-width: 0;
  background: #ffffff;
  border: 1px solid #dde3ea;
  border-radius: 22px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.03);
}

.thread-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 18px;
  background: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  gap: 12px;
  flex-wrap: wrap;
}

.thread-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.thread-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  font-weight: 800;
  font-size: 15px;
}

.thread-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.thread-cust-name {
  font-size: 15.5px;
  font-weight: 800;
  color: #0f172a;
  margin: 0;
}

.thread-honorific-pill,
.thread-stage-pill {
  font-size: 11.5px;
  padding: 2px 9px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #334155;
}

.thread-stage-pill {
  background: #e8f0fe;
  color: #0b57d0;
  font-weight: 700;
}

.thread-sub-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #64748b;
  margin-top: 3px;
}

.mode-indicator-text.is-human {
  color: #047857;
  font-weight: 700;
}

.mode-indicator-text.is-bot {
  color: #0b57d0;
  font-weight: 600;
}

.thread-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.btn-control-mode {
  padding: 7px 13px;
  border-radius: 999px;
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s ease;
}

.btn-takeover {
  background: #fef3c7;
  color: #92400e;
  border-color: #fcd34d;
}

.btn-takeover:hover {
  background: #fde68a;
}

.btn-resume-ai {
  background: #e8f0fe;
  color: #0b57d0;
  border-color: #a8c7fa;
}

.btn-mark-converted {
  background: #ecfdf5;
  color: #047857;
  border-color: #6ee7b7;
}

.converted-badge-header {
  font-size: 12px;
  font-weight: 800;
  padding: 6px 12px;
  border-radius: 999px;
  background: #d1fae5;
  color: #065f46;
}

.btn-toggle-insight {
  padding: 7px 11px;
  border-radius: 999px;
  border: 1px solid #cbd5e1;
  background: #f8fafc;
  color: #334155;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.btn-toggle-insight.active {
  background: #0b57d0;
  color: #ffffff;
  border-color: #0b57d0;
}

.handoff-alert-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 18px;
  background: linear-gradient(90deg, #fff1f2 0%, #fffbeb 100%);
  border-bottom: 1px solid #fecdd3;
}

.handoff-alert-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.handoff-siren {
  font-size: 20px;
}

.handoff-alert-title {
  font-size: 13px;
  font-weight: 800;
  color: #991b1b;
  display: flex;
  align-items: center;
  gap: 8px;
}

.handoff-phone-highlight {
  background: #dc2626;
  color: #ffffff;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11.5px;
}

.handoff-alert-reason {
  font-size: 12px;
  color: #7f1d1d;
}

.btn-accept-handoff {
  padding: 7px 14px;
  border-radius: 999px;
  background: #dc2626;
  color: #ffffff;
  border: none;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
}

/* Messages Stream */
.thread-messages-stream {
  flex: 1;
  overflow-y: auto;
  padding: 18px 20px;
  background: #f8fafd;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.msg-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  max-width: 82%;
}

.msg-customer {
  align-self: flex-start;
}

.msg-ai-bot,
.msg-human-sale {
  align-self: flex-end;
}

.msg-side-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  flex-shrink: 0;
}

.cust-avatar {
  background: #334155;
  color: #ffffff;
}

.ai-avatar {
  background: #e8f0fe;
  border: 1px solid #a8c7fa;
}

.sale-avatar {
  background: #d1fae5;
  border: 1px solid #6ee7b7;
}

.msg-bubble-col {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.msg-meta-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: #64748b;
}

.msg-ai-bot .msg-meta-header,
.msg-human-sale .msg-meta-header {
  justify-content: flex-end;
}

.msg-actor-badge {
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.badge-cust { color: #334155; }
.badge-ai { color: #0b57d0; }
.badge-sale { color: #047857; }

.msg-latency-tag,
.msg-stage-tag,
.sale-gold-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  background: #e2e8f0;
  color: #334155;
}

.sale-gold-tag {
  background: #fef3c7;
  color: #92400e;
}

.msg-bubble-box {
  padding: 12px 15px;
  border-radius: 18px;
  font-size: 13.5px;
  line-height: 1.55;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}

.msg-customer .msg-bubble-box {
  background: #ffffff;
  color: #0f172a;
  border: 1px solid #e2e8f0;
  border-bottom-left-radius: 4px;
}

.msg-ai-bot .msg-bubble-box {
  background: #e8f0fe;
  color: #041e49;
  border: 1px solid #c2d7f8;
  border-bottom-right-radius: 4px;
}

.msg-human-sale .msg-bubble-box {
  background: #ecfdf5;
  color: #064e3b;
  border: 1px solid #6ee7b7;
  border-bottom-right-radius: 4px;
}

.msg-para {
  margin: 0;
}

.msg-para + .msg-para {
  margin-top: 8px;
}

.ai-msg-footer-bar {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  margin-top: 2px;
}

.ai-hooks-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.mini-hook-chip {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #475569;
}

.ai-action-buttons {
  display: flex;
  gap: 6px;
}

.btn-ai-msg-action {
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  border: 1px solid #cbd5e1;
  background: #ffffff;
  color: #334155;
}

.btn-teach-inline:hover {
  border-color: #f59e0b;
  background: #fffbeb;
  color: #b45309;
}

.btn-harness-inline:hover {
  border-color: #7c3aed;
  background: #f5f3ff;
  color: #6d28d9;
}

/* Composer Footer */
.thread-composer-footer {
  padding: 10px 16px 14px;
  background: #ffffff;
  border-top: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.composer-mode-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.composer-mode-tabs {
  display: flex;
  gap: 6px;
}

.composer-mode-btn {
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  border: 1px solid #cbd5e1;
  background: #f8fafc;
  color: #475569;
  cursor: pointer;
}

.composer-mode-btn.mode-sale.active {
  background: #059669;
  color: #ffffff;
  border-color: #059669;
}

.composer-mode-btn.mode-customer.active {
  background: #0b57d0;
  color: #ffffff;
  border-color: #0b57d0;
}

.composer-mode-hint {
  font-size: 11.5px;
  color: #64748b;
}

.composer-input-row {
  display: flex;
  gap: 10px;
  align-items: stretch;
}

.composer-textarea {
  flex: 1;
  padding: 10px 14px;
  border-radius: 14px;
  border: 1.5px solid #cbd5e1;
  font-size: 13.5px;
  resize: none;
  outline: none;
  font-family: inherit;
}

.composer-textarea.is-sale-input:focus {
  border-color: #059669;
  background: #f0fdf4;
}

.composer-textarea.is-cust-input:focus {
  border-color: #0b57d0;
  background: #f8fafd;
}

.composer-send-btns {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.btn-send-main,
.btn-send-convert {
  padding: 8px 16px;
  border-radius: 12px;
  border: none;
  font-size: 12.5px;
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
}

.btn-send-sale {
  background: #059669;
  color: #ffffff;
}

.btn-send-cust {
  background: #0b57d0;
  color: #ffffff;
}

.btn-send-convert {
  background: #d1fae5;
  color: #065f46;
  border: 1px solid #6ee7b7;
  font-size: 11.5px;
  padding: 5px 12px;
}

/* ========================================================================= */
/* RIGHT COLUMN: CUSTOMER INSIGHT & COLD PATH TRAINING                       */
/* ========================================================================= */
.inbox-right-insight {
  width: 330px;
  min-width: 300px;
  max-width: 350px;
  background: #ffffff;
  border: 1px solid #dde3ea;
  border-radius: 22px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.03);
}

.insight-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 13px 16px;
  background: #f8fafd;
  border-bottom: 1px solid #e2e8f0;
}

.insight-header-title {
  font-size: 13.5px;
  font-weight: 800;
  color: #0f172a;
}

.insight-live-badge {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  background: #e8f0fe;
  color: #0b57d0;
}

.insight-panel-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.insight-card {
  padding: 12px 14px;
  border-radius: 16px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
}

.insight-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.insight-card-label {
  font-size: 12px;
  font-weight: 800;
  color: #334155;
  margin-bottom: 6px;
}

.mt-10 {
  margin-top: 10px;
}

.temp-score-group {
  display: flex;
  align-items: center;
  gap: 5px;
}

.temp-big-num {
  font-size: 18px;
  font-weight: 900;
}

.text-hot { color: #dc2626; }
.text-warm { color: #d97706; }
.text-cold { color: #0284c7; }

.temp-delta-pill {
  font-size: 11px;
  font-weight: 800;
  padding: 1px 6px;
  border-radius: 999px;
}

.delta-up { background: #dcfce7; color: #15803d; }
.delta-down { background: #fee2e2; color: #b91c1c; }

.temp-status-tag {
  font-size: 10.5px;
  font-weight: 800;
  padding: 2px 7px;
  border-radius: 999px;
}

.temp-progress-track {
  height: 8px;
  border-radius: 999px;
  background: #f1f5f9;
  overflow: hidden;
}

.temp-progress-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.35s ease;
}

.bar-hot { background: linear-gradient(90deg, #f59e0b, #ef4444); }
.bar-warm { background: linear-gradient(90deg, #38bdf8, #f59e0b); }
.bar-cold { background: #38bdf8; }

.temp-scale-labels {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: #64748b;
  margin-top: 5px;
}

.lead-info-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.lead-info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
}

.lead-key {
  color: #64748b;
}

.lead-val {
  color: #0f172a;
}

.phone-found {
  font-weight: 800;
  color: #b45309;
  background: #fef3c7;
  padding: 2px 8px;
  border-radius: 999px;
}

.phone-missing {
  color: #94a3b8;
  font-style: italic;
}

.topic-highlight {
  font-weight: 700;
  color: #0b57d0;
}

.archetype-badge-box {
  padding: 8px 10px;
  border-radius: 10px;
  background: #f0f4f9;
  color: #0b57d0;
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 8px;
}

.strategy-recommend-box {
  padding: 8px 10px;
  border-radius: 10px;
  background: #fffbeb;
  border: 1px solid #fde68a;
}

.strategy-title {
  font-size: 11px;
  font-weight: 800;
  color: #92400e;
  margin-bottom: 3px;
}

.strategy-text {
  font-size: 11.5px;
  color: #78350f;
  margin: 0;
  line-height: 1.45;
}

.insight-tags-wrap {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.insight-pain-chip,
.insight-concern-chip {
  font-size: 11.5px;
  padding: 5px 9px;
  border-radius: 8px;
  line-height: 1.4;
}

.insight-pain-chip {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  color: #1e293b;
}

.insight-concern-chip {
  background: #fff1f2;
  border: 1px solid #fecdd3;
  color: #9f1239;
}

.insight-empty-note {
  font-size: 11.5px;
  color: #94a3b8;
  margin: 0;
}

.coldpath-training-card {
  background: linear-gradient(180deg, #f8fafd 0%, #eff6ff 100%);
  border-color: #bfdbfe;
}

.coldpath-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.coldpath-icon {
  font-size: 18px;
}

.coldpath-title {
  font-size: 12.5px;
  font-weight: 800;
  color: #1e3a8a;
}

.coldpath-sub {
  font-size: 10.5px;
  color: #3b82f6;
}

.coldpath-desc {
  font-size: 11.5px;
  color: #334155;
  line-height: 1.45;
  margin: 0 0 10px;
}

.btn-extract-coldpath {
  width: 100%;
  padding: 8px 12px;
  border-radius: 10px;
  border: none;
  background: #0b57d0;
  color: #ffffff;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.btn-extract-coldpath:hover {
  background: #0842a0;
}

.extraction-result-box {
  margin-top: 8px;
  padding: 8px;
  border-radius: 8px;
  background: #ecfdf5;
  border: 1px solid #6ee7b7;
}

.ext-res-title {
  font-size: 11.5px;
  font-weight: 700;
  color: #065f46;
}

.btn-open-staging-link {
  margin-top: 4px;
  border: none;
  background: transparent;
  color: #0b57d0;
  font-size: 11.5px;
  font-weight: 700;
  cursor: pointer;
  padding: 0;
  text-decoration: underline;
}

.thread-empty-placeholder,
.conv-empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  text-align: center;
  color: #64748b;
}

.placeholder-icon,
.empty-emoji {
  font-size: 36px;
  margin-bottom: 8px;
}

/* ========================================================================= */
/* MOBILE NAVIGATION & RESPONSIVE BREAKPOINTS (<= 960px & <= 640px)          */
/* ========================================================================= */
.mobile-inbox-tabs {
  display: none;
}

.btn-mobile-back-list,
.btn-mobile-back-chat {
  display: none;
}

@media (max-width: 960px) {
  .omnichannel-inbox-layout {
    flex-direction: column;
    gap: 8px;
    height: 100%;
    overflow: hidden;
  }

  .mobile-inbox-tabs {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px;
    background: #ffffff;
    border: 1px solid #dde3ea;
    border-radius: 16px;
    flex-shrink: 0;
    box-shadow: 0 2px 8px rgba(15, 23, 42, 0.04);
  }

  .mobile-inbox-tab {
    flex: 1;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 5px;
    padding: 8px 6px;
    border-radius: 11px;
    border: 1px solid transparent;
    background: transparent;
    color: #475569;
    font-size: 12px;
    font-weight: 700;
    cursor: pointer;
    min-width: 0;
    white-space: nowrap;
  }

  .mobile-inbox-tab.active {
    background: #0b57d0;
    color: #ffffff;
    box-shadow: 0 2px 6px rgba(11, 87, 208, 0.22);
  }

  .m-tab-badge {
    font-size: 10.5px;
    font-weight: 800;
    padding: 1px 6px;
    border-radius: 999px;
    background: rgba(15, 23, 42, 0.08);
    color: inherit;
  }

  .mobile-inbox-tab.active .m-tab-badge {
    background: rgba(255, 255, 255, 0.24);
    color: #ffffff;
  }

  .m-tab-urgent {
    background: #dc2626;
    color: #ffffff;
    font-size: 10px;
    font-weight: 800;
    padding: 1px 5px;
    border-radius: 999px;
  }

  .m-tab-sub {
    font-size: 11px;
    font-weight: 600;
    opacity: 0.9;
    max-width: 82px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .inbox-left-panel,
  .inbox-center-thread,
  .inbox-right-insight {
    width: 100% !important;
    min-width: 0 !important;
    max-width: 100% !important;
    flex: 1;
    min-height: 0;
    border-radius: 18px;
  }

  .omnichannel-inbox-layout.mobile-pane-list .inbox-center-thread,
  .omnichannel-inbox-layout.mobile-pane-list .inbox-right-insight {
    display: none !important;
  }

  .omnichannel-inbox-layout.mobile-pane-chat .inbox-left-panel,
  .omnichannel-inbox-layout.mobile-pane-chat .inbox-right-insight {
    display: none !important;
  }

  .omnichannel-inbox-layout.mobile-pane-insight .inbox-left-panel,
  .omnichannel-inbox-layout.mobile-pane-insight .inbox-center-thread {
    display: none !important;
  }

  .btn-mobile-back-list {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    border-radius: 50%;
    border: 1px solid #cbd5e1;
    background: #f8fafc;
    color: #0f172a;
    font-size: 15px;
    font-weight: 800;
    cursor: pointer;
    flex-shrink: 0;
  }

  .btn-mobile-back-chat {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 10px;
    border-radius: 999px;
    border: 1px solid #cbd5e1;
    background: #ffffff;
    color: #0b57d0;
    font-size: 11.5px;
    font-weight: 700;
    cursor: pointer;
    margin-right: 6px;
  }

  /* Prevent iOS Safari / Android Chrome auto-zoom on input focus */
  .conv-search-input,
  .new-cust-input,
  .new-cust-textarea,
  .composer-textarea {
    font-size: 16px !important;
  }
}

@media (max-width: 640px) {
  .thread-header {
    padding: 10px 12px;
    gap: 8px;
  }

  .thread-avatar {
    width: 36px;
    height: 36px;
    font-size: 13px;
  }

  .thread-cust-name {
    font-size: 14px;
  }

  .thread-psid {
    display: none;
  }

  .thread-sub-row {
    font-size: 11px;
  }

  .thread-header-actions {
    width: 100%;
    justify-content: space-between;
    gap: 6px;
  }

  .btn-control-mode,
  .btn-toggle-insight {
    padding: 6px 10px;
    font-size: 11.5px;
    flex: 1;
    text-align: center;
  }

  .handoff-alert-banner {
    flex-direction: column;
    align-items: stretch;
    padding: 9px 12px;
    gap: 8px;
  }

  .btn-accept-handoff {
    width: 100%;
    text-align: center;
  }

  .thread-messages-stream {
    padding: 12px 10px;
    gap: 12px;
  }

  .msg-row {
    max-width: 94%;
    gap: 6px;
  }

  .msg-side-avatar {
    width: 26px;
    height: 26px;
    font-size: 11px;
  }

  .msg-bubble-box {
    padding: 10px 12px;
    font-size: 13.5px;
  }

  .thread-composer-footer {
    padding: 8px 10px 10px;
    gap: 6px;
  }

  .composer-mode-tabs {
    width: 100%;
  }

  .composer-mode-btn {
    flex: 1;
    padding: 6px 8px;
    font-size: 11.5px;
    text-align: center;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .composer-mode-hint {
    display: none;
  }

  .composer-input-row {
    gap: 6px;
  }

  .composer-textarea {
    padding: 8px 11px;
  }

  .btn-send-main,
  .btn-send-convert {
    padding: 7px 11px;
    font-size: 12px;
  }
}
</style>
