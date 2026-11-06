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
        <template v-if="leftTab === 'documents'">
          <span>📄 DS Tài liệu</span>
          <span class="m-tab-badge">{{ scopedDocuments.length }}</span>
        </template>
        <template v-else>
          <span>📋 DS Khách</span>
          <span class="m-tab-badge">{{ conversations.length }}</span>
          <span v-if="handoffCount > 0" class="m-tab-urgent">{{ handoffCount }}</span>
        </template>
      </button>

      <button
        type="button"
        class="mobile-inbox-tab"
        :class="{ active: mobilePane === 'chat' }"
        @click="mobilePane = 'chat'"
      >
        <template v-if="leftTab === 'documents'">
          <span>📑 Chi tiết &amp; Chunks</span>
          <span v-if="docChunks.length > 0" class="m-tab-badge">{{ docChunks.length }}</span>
        </template>
        <template v-else>
          <span>💬 Chat</span>
          <span v-if="activeSession" class="m-tab-sub">
            {{ getShortCustomerName(activeSession.customer_name) }}
          </span>
        </template>
      </button>

      <button
        v-if="leftTab === 'conversations'"
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
    <aside
      class="inbox-left-panel"
      :class="{ 'is-collapsed-desktop': !showLeftListPanel }"
    >
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
          <span class="tab-pill-count">{{ scopedDocuments.length || scopedDocCount }}</span>
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
    <!-- CỘT GIỮA / KHUNG LỚN BÊN PHẢI: CHAT MESSENGER HOẶC CHI TIẾT TÀI LIỆU -->
    <!-- =================================================================== -->
    <section class="inbox-center-thread" :class="{ 'is-doc-inspector-mode': leftTab === 'documents' }">
      <!-- MODE A: XEM CHI TIẾT NỘI DUNG TÀI LIỆU / CHUNKS / BẢNG GIÁ (KHI Ở TAB TÀI LIỆU PAGE) -->
      <div v-if="leftTab === 'documents'" class="doc-inspector-workspace">
        <template v-if="selectedInspectDoc">
          <!-- Top Document Inspector Header -->
          <header class="doc-inspector-header">
            <div class="doc-inspector-title-group">
              <button
                type="button"
                class="btn-mobile-back-list"
                title="Quay lại danh sách tài liệu"
                @click="mobilePane = 'list'"
              >
                ←
              </button>

              <div class="doc-inspector-ext-badge">
                {{ getDocExtension(selectedInspectDoc.filename) }}
              </div>

              <div class="doc-inspector-meta-col">
                <h3 class="doc-inspector-filename" :title="selectedInspectDoc.filename">
                  {{ selectedInspectDoc.filename }}
                </h3>
                <div class="doc-inspector-pills-row">
                  <span v-if="selectedInspectDoc.status === 'READY'" class="doc-stat-pill pill-ready">
                    ✅ Sẵn sàng ({{ selectedInspectDoc.pages || 1 }} trang)
                  </span>
                  <span v-else-if="selectedInspectDoc.status === 'PARSING'" class="doc-stat-pill pill-parsing">
                    ⏳ Đang phân tích tài liệu...
                  </span>
                  <span v-else class="doc-stat-pill pill-failed">
                    ⚠️ Lỗi phân tích
                  </span>

                  <span class="doc-stat-pill pill-chunks">
                    📑 {{ docChunks.length }} chunks đã trích xuất
                  </span>

                  <span v-if="pricingOrTableChunksCount > 0" class="doc-stat-pill pill-pricing">
                    💰 {{ pricingOrTableChunksCount }} chunk bảng giá / bảng biểu
                  </span>

                  <span v-if="selectedInspectDoc.is_active === false" class="doc-stat-pill pill-paused">
                    ⏸️ Tạm ngưng tra cứu
                  </span>
                  <span v-else-if="selectedInspectDoc.is_expired" class="doc-stat-pill pill-failed">
                    ⌛ Đã hết hạn hiệu lực
                  </span>
                </div>
              </div>
            </div>

            <div class="doc-inspector-header-actions">
              <button
                type="button"
                class="btn-doc-header-act"
                :disabled="isLoadingChunks"
                title="Tải lại danh sách Chunks & Bảng giá từ máy chủ"
                @click="loadSelectedDocumentChunks"
              >
                🔄 Làm mới
              </button>
              <button
                type="button"
                class="btn-doc-header-act"
                title="Cấu hình thời hạn hiệu lực & trạng thái hoạt động của tài liệu"
                @click="$emit('view-doc-detail', selectedInspectDoc)"
              >
                ⚙️ Cài đặt hiệu lực
              </button>
              <button
                type="button"
                class="btn-doc-header-act btn-doc-header-del"
                title="Xóa tài liệu này khỏi kho tri thức"
                @click="$emit('delete-doc', selectedInspectDoc)"
              >
                🗑️ Xóa
              </button>
            </div>
          </header>

          <!-- Filter Tabs & Search Bar for Chunks / Pricing Tables -->
          <div class="doc-inspector-toolbar">
            <div class="doc-chunk-filter-tabs">
              <button
                type="button"
                class="chunk-filter-tab"
                :class="{ active: chunkFilterMode === 'ALL' }"
                @click="chunkFilterMode = 'ALL'"
              >
                📑 Tất cả nội dung / Chunks ({{ docChunks.length }})
              </button>
              <button
                type="button"
                class="chunk-filter-tab"
                :class="{ active: chunkFilterMode === 'PRICING' }"
                @click="chunkFilterMode = 'PRICING'"
              >
                💰 Bảng giá &amp; Bảng biểu ({{ pricingOrTableChunksCount }})
              </button>
            </div>

            <div class="doc-chunk-search-box">
              <input
                v-model="chunkSearchQuery"
                type="text"
                class="doc-chunk-search-input"
                placeholder="Tìm nhanh dịch vụ, mã gói, đơn giá, quy định trong tài liệu..."
              />
              <button v-if="chunkSearchQuery" type="button" class="doc-chunk-search-clear" @click="chunkSearchQuery = ''">
                ×
              </button>
            </div>
          </div>

          <!-- Extracted Chunks & Pricing Tables Body -->
          <div class="doc-chunks-scroll">
            <div v-if="selectedInspectDoc.status === 'PARSING'" class="doc-chunks-state-box">
              <div class="placeholder-icon">⏳</div>
              <h4>Tài liệu đang được phân tích &amp; tách Chunks...</h4>
              <p>Hệ thống sẽ tự động hiển thị nội dung chi tiết và bảng giá ngay khi hoàn tất.</p>
            </div>

            <div v-else-if="isLoadingChunks" class="doc-chunks-state-box">
              <div class="placeholder-icon">⏳</div>
              <h4>Đang tải nội dung tài liệu &amp; danh sách Chunks...</h4>
            </div>

            <div v-else-if="chunksError" class="doc-chunks-state-box is-error">
              <div class="placeholder-icon">⚠️</div>
              <h4>Không thể tải danh sách Chunks</h4>
              <p>{{ chunksError }}</p>
              <button type="button" class="btn-doc-header-act" @click="loadSelectedDocumentChunks">
                🔄 Thử lại
              </button>
            </div>

            <div v-else-if="filteredDocChunks.length === 0" class="doc-chunks-state-box">
              <div class="placeholder-icon">📄</div>
              <h4>Không có chunk nào khớp bộ lọc hiện tại</h4>
              <p v-if="chunkSearchQuery || chunkFilterMode !== 'ALL'">
                Thử xóa từ khóa tìm kiếm hoặc chuyển về tab <strong>"Tất cả nội dung / Chunks"</strong>.
              </p>
              <p v-else>Tài liệu này hiện chưa có đoạn văn bản nào được trích xuất.</p>
            </div>

            <article
              v-for="(chunk, cIdx) in filteredDocChunks"
              :key="chunk.chunk_id || cIdx"
              class="doc-chunk-card"
              :class="{ 'has-pricing-highlight': chunk.has_pricing || chunk.has_table }"
            >
              <div class="chunk-card-header">
                <div class="chunk-breadcrumb-row">
                  <span class="chunk-idx-badge">#{{ (chunk.chunk_index ?? cIdx) + 1 }}</span>
                  <span class="chunk-page-tag">Trang {{ chunk.page || 1 }}</span>
                  <span v-if="chunk.heading_path && chunk.heading_path.length > 0" class="chunk-heading-path">
                    {{ chunk.heading_path.join(' › ') }}
                  </span>
                </div>

                <div class="chunk-semantic-tags">
                  <span v-if="chunk.has_pricing" class="chunk-tag tag-pricing">💰 Đơn giá / Bảng giá</span>
                  <span v-if="chunk.has_table" class="chunk-tag tag-table">📊 Bảng biểu</span>
                  <span v-if="chunk.metadata && chunk.metadata.chunk_type" class="chunk-tag tag-type">
                    {{ chunk.metadata.chunk_type }}
                  </span>
                </div>
              </div>

              <!-- Extracted Price / Code Metadata Chips if available -->
              <div
                v-if="hasChunkExtractedMeta(chunk)"
                class="chunk-extracted-meta-bar"
              >
                <span
                  v-for="(code, codeIdx) in getChunkMetaArray(chunk, 'match_codes')"
                  :key="'code-' + codeIdx"
                  class="meta-extract-chip chip-code"
                >
                  🏷️ Mã: {{ code }}
                </span>
                <span
                  v-for="(price, prIdx) in getChunkMetaArray(chunk, 'match_prices').slice(0, 8)"
                  :key="'pr-' + prIdx"
                  class="meta-extract-chip chip-price"
                >
                  💵 {{ formatVndChip(price) }}
                </span>
              </div>

              <!-- Structured Content Blocks (Markdown Tables + Headings + Lists + Paragraphs) -->
              <div class="chunk-content-body">
                <template v-for="(block, bIdx) in parseChunkBlocks(chunk.text)" :key="bIdx">
                  <div v-if="block.type === 'table'" class="chunk-table-wrap">
                    <table class="chunk-md-table">
                      <thead>
                        <tr>
                          <th v-for="(hCell, hIdx) in block.headers" :key="'h-' + hIdx">
                            {{ hCell }}
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="(row, rIdx) in block.rows" :key="'r-' + rIdx">
                          <td v-for="(cell, cellIdx) in row" :key="'c-' + cellIdx">
                            {{ cell }}
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </div>

                  <h4 v-else-if="block.type === 'heading'" class="chunk-block-heading">
                    {{ block.text }}
                  </h4>

                  <ul v-else-if="block.type === 'list'" class="chunk-block-list">
                    <li v-for="(item, lIdx) in block.items" :key="'li-' + lIdx">
                      {{ item }}
                    </li>
                  </ul>

                  <p v-else class="chunk-block-para">
                    {{ block.text }}
                  </p>
                </template>
              </div>
            </article>
          </div>
        </template>

        <!-- Empty State when active Page has 0 documents -->
        <div v-else class="thread-empty-placeholder">
          <div class="placeholder-icon">📄</div>
          <h3>Chưa có tài liệu nào trong không gian này</h3>
          <p>
            Hãy tải lên tệp <strong>PDF, DOCX, XLSX, MD</strong> ở khung Upload bên cột trái để hệ thống tự động tách Chunks và trích xuất Bảng giá tại đây.
          </p>
        </div>
      </div>

      <!-- MODE B: CHI TIẾT CUỘC TRÒ CHUYỆN MESSENGER (KHI Ở TAB HỘI THOẠI KH) -->
      <template v-else-if="activeSession">
        <!-- Messenger Top Header (Compact Single-Line Laptop Layout) -->
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
            <button
              type="button"
              class="btn-toggle-left-panel"
              :class="{ active: showLeftListPanel }"
              title="Ẩn/Hiện cột Danh sách Khách hàng bên trái để mở rộng khung hội thoại"
              @click="showLeftListPanel = !showLeftListPanel"
            >
              {{ showLeftListPanel ? '◀ DS Khách' : '▶ DS Khách' }}
            </button>
            <div class="thread-avatar" :class="getAvatarColorClass(activeSession)">
              {{ getInitials(activeSession.customer_name) }}
            </div>
            <div class="thread-cust-meta">
              <div class="thread-name-row">
                <h3 class="thread-cust-name">{{ activeSession.customer_name }}</h3>
                <span class="thread-honorific-pill">Xưng hô: <strong>{{ activeSession.honorific || (activePage && activePage.persona_config && activePage.persona_config.default_customer_pronoun) || 'mình' }}</strong></span>
                <span class="thread-stage-pill" :class="'stage-' + (activeSession.sales_stage || 'CONSULTING').toLowerCase()">
                  📍 {{ formatSalesStage(activeSession.sales_stage) }}
                </span>
                <span v-if="activeSession.control_mode === 'HUMAN_TAKEOVER'" class="mode-indicator-text is-human">
                  · 👨‍💼 Sale trực
                </span>
                <span v-else class="mode-indicator-text is-bot">
                  · 🤖 AI V2 trực
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
              ✋ Sale Tiếp Nhận
            </button>
            <button
              v-else
              class="btn-control-mode btn-resume-ai"
              :disabled="isUpdatingControl"
              title="Giao lại cuộc hội thoại này cho AI Bot tự động trả lời"
              @click="handleToggleControlMode('BOT_ACTIVE')"
            >
              🤖 Bật lại AI
            </button>

            <!-- Mark Converted Button -->
            <button
              v-if="activeSession.status !== 'CONVERTED'"
              class="btn-control-mode btn-mark-converted"
              :disabled="isUpdatingControl"
              title="Đánh dấu ca chat này đã chốt đơn/đặt lịch thành công (Dữ liệu vàng cho AI học ban đêm)"
              @click="handleMarkConverted"
            >
              ✅ Đã Chốt
            </button>
            <span v-else class="converted-badge-header">
              🏆 Đã chốt
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

        <!-- Messenger Multi-Actor Messages Stream (>= 80% Laptop Viewport Height) -->
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
                  👤 {{ msg.sender_name || activeSession.customer_name }}
                </span>
                <span v-else-if="msg.sender_role === 'AI_BOT'" class="msg-actor-badge badge-ai">
                  🤖 {{ msg.sender_name || 'AI Trợ Lý Fanpage' }}
                </span>
                <span v-else class="msg-actor-badge badge-sale">
                  👨‍💼 {{ msg.sender_name || 'Nhân viên Sale' }}
                  <span class="sale-gold-tag">⭐ Mẫu thực chiến</span>
                </span>
                <span class="msg-time-label">{{ formatTimeOnly(msg.created_at) }}</span>
              </div>

              <!-- Message Bubble Content (Supports Multi-Bubble Messenger Output: 1 independent .msg-bubble-box per normalized bubble) -->
              <div
                v-for="(bubbleText, bIdx) in getMessageBubbles(msg)"
                :key="bIdx"
                class="msg-bubble-box"
              >
                <p class="msg-para">{{ bubbleText }}</p>
              </div>

              <!-- 1-Line 24px Compact AI Metadata Pill (Collapsed by default) -->
              <div v-if="msg.sender_role === 'AI_BOT' && !msg._delivering_in_progress" class="ai-msg-footer-bar">
                <div class="ai-compact-pill-row">
                  <span
                    class="ai-compact-metrics-pill"
                    title="Bộ máy tư vấn · Tổng thời gian xử lý lượt"
                  >
                    {{ (msg.triggered_hooks || []).includes('SALES_CORE_V1') ? 'Sales Core' : '🛡️ V2' }} · ⏱️ {{ formatTurnDurationSec(msg) }}s
                  </span>
                  <button
                    type="button"
                    class="btn-ai-pill-toggle"
                    :class="{ expanded: isAiDetailExpanded(msg, mIdx) }"
                    title="Mở/Đóng chi tiết kỹ năng, quy tắc & token của lượt này"
                    @click="toggleAiDetail(msg, mIdx)"
                  >
                    Chi tiết AI {{ isAiDetailExpanded(msg, mIdx) ? '▴' : '▾' }}
                  </button>
                  <button
                    type="button"
                    class="btn-ai-pill-report"
                    title="Báo sai câu trả lời này và dạy lại quy tắc cho AI"
                    @click="handleTeachAiOnMessage(msg, mIdx)"
                  >
                    Báo sai
                  </button>
                </div>

                <!-- On-Demand Expandable AI Telemetry Drawer -->
                <div v-if="isAiDetailExpanded(msg, mIdx)" class="ai-expanded-detail-drawer">
                  <div v-if="!(msg.triggered_hooks || []).includes('SALES_CORE_V1')" class="ai-detail-line">
                    <strong>⚡ Kỹ năng ({{ getMsgActivatedSkills(msg).length }}):</strong>
                    <span v-if="getMsgActivatedSkills(msg).length > 0">
                      {{ getMsgActivatedSkills(msg).map(sk => (typeof sk === 'string' ? sk : (sk.name || sk.code))).join(' · ') }}
                    </span>
                    <span v-else class="ai-detail-muted">Không bật skill thừa (phản hồi trực tiếp)</span>
                  </div>
                  <div v-if="msg.triggered_hooks && msg.triggered_hooks.length > 0" class="ai-detail-line">
                    <strong>🛡️ Guardrail:</strong>
                    <span>{{ msg.triggered_hooks.join(' · ') }}</span>
                  </div>
                  <div v-if="getMsgAppliedRules(msg).length > 0" class="ai-detail-line">
                    <strong>📋 Quy tắc ({{ getMsgAppliedRules(msg).length }}):</strong>
                    <span>
                      {{ getMsgAppliedRules(msg).map(r => (typeof r === 'string' ? r : (r.rule_code || r.ruleCode || r.category || 'RULE'))).join(' · ') }}
                    </span>
                  </div>
                  <div v-if="msg.turn_profiling" class="ai-detail-line ai-detail-profiling">
                    <strong>⏱️ Profiling:</strong>
                    <span v-if="(msg.triggered_hooks || []).includes('TOKEN_USAGE_UNAVAILABLE')">
                      Chưa có thống kê token cho lượt này · Xử lý {{ msg.turn_profiling.model_latency_ms ?? 0 }}ms
                    </span>
                    <span v-else>
                      In {{ msg.turn_profiling.input_tokens ?? msg.turn_profiling.inputTokens ?? 0 }} tok ·
                      Out {{ msg.turn_profiling.output_tokens ?? msg.turn_profiling.outputTokens ?? 0 }} tok ·
                      Reflect {{ msg.turn_profiling.reflection_tokens ?? msg.turn_profiling.reflectionTokens ?? 0 }} tok ·
                      LLM {{ msg.turn_profiling.model_latency_ms ?? msg.turn_profiling.modelLatencyMs ?? msg.latency_ms ?? 0 }}ms
                    </span>
                  </div>
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

          <!-- Typing Indicator when sending or waiting typing_delay_ms between bubbles -->
          <div v-if="isSendingMessage || isDeliveringBotBubbles" class="msg-row msg-ai-bot">
            <div class="msg-bubble-col">
              <div class="msg-bubble-box typing-bubble">
                <span v-if="isDeliveringBotBubbles && inboxRemainingDelaySec > 0">
                  ⏳ AI đang chờ nhịp đọc &amp; soạn bong bóng tiếp theo ({{ inboxRemainingDelaySec }}s)...
                </span>
                <span v-else-if="isDeliveringBotBubbles">
                  💬 AI đang nhập bong bóng tiếp theo...
                </span>
                <span v-else>⏳ Đang xử lý tin nhắn...</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Compact Single-Row Dual-Mode Messenger Composer Footer -->
        <footer class="thread-composer-footer">
          <div class="composer-input-row">
            <div class="composer-mode-tabs">
              <button
                type="button"
                class="composer-mode-btn mode-sale"
                :class="{ active: composerMode === 'HUMAN_SALE' }"
                title="Gửi tư cách Nhân viên Sale thật (Tự động chuyển sang Sale trực & lưu mẫu huấn luyện)"
                @click="composerMode = 'HUMAN_SALE'"
              >
                👨‍💼 Sale
              </button>
              <button
                type="button"
                class="composer-mode-btn mode-customer"
                :class="{ active: composerMode === 'CUSTOMER' }"
                title="Giả lập khách hàng nhắn tiếp qua Messenger để test Bot AI tự động trả lời"
                @click="composerMode = 'CUSTOMER'"
              >
                👤 Khách
              </button>
            </div>

            <textarea
              v-model="composerText"
              rows="1"
              class="composer-textarea"
              :class="composerMode === 'HUMAN_SALE' ? 'is-sale-input' : 'is-cust-input'"
              :placeholder="
                composerMode === 'HUMAN_SALE'
                  ? `Trả lời ${activeSession.customer_name} (Tư cách Sale)...`
                  : `Giả lập ${activeSession.customer_name} nhắn vào Fanpage...`
              "
              @compositionstart="isImeComposing = true"
              @compositionend="handleImeCompositionEnd($event)"
              @keydown.enter.exact="handleComposerEnter($event)"
            ></textarea>

            <div class="composer-send-btns">
              <button
                class="btn-send-main"
                :class="composerMode === 'HUMAN_SALE' ? 'btn-send-sale' : 'btn-send-cust'"
                :disabled="isSendingMessage || !composerText.trim()"
                @click="handleSendComposerMessage(false)"
              >
                <span v-if="composerMode === 'HUMAN_SALE'">📤 Gửi</span>
                <span v-else>💬 Khách gửi</span>
              </button>

              <button
                v-if="composerMode === 'HUMAN_SALE'"
                class="btn-send-convert"
                :disabled="isSendingMessage || !composerText.trim()"
                title="Gửi tin nhắn này và đánh dấu Đã chốt đơn thành công"
                @click="handleSendComposerMessage(true)"
              >
                ✅ Chốt
              </button>
            </div>
          </div>
        </footer>
      </template>

      <div v-else class="thread-empty-placeholder">
        <div class="placeholder-icon">💬</div>
        <h3>Chọn một cuộc hội thoại ở cột bên trái</h3>
        <p>Xem toàn bộ lịch sử chat giữa Khách hàng, AI Bot và Nhân viên Sale theo thời gian thực.</p>
        <button
          v-if="!showLeftListPanel"
          type="button"
          class="btn-toggle-left-panel"
          @click="showLeftListPanel = true"
        >
          ▶ Mở Danh sách Khách hàng
        </button>
      </div>
    </section>

    <!-- =================================================================== -->
    <!-- CỘT PHẢI: RADAR THẤU HIỂU INSIGHT KHÁCH HÀNG & ĐÀO TẠO COLD PATH     -->
    <!-- =================================================================== -->
    <aside v-if="leftTab === 'conversations' && (showInsightPanel || mobilePane === 'insight') && activeSession" class="inbox-right-insight">
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
              <span class="lead-val"><strong>{{ activeSession.honorific || (activePage && activePage.persona_config && activePage.persona_config.default_customer_pronoun) || 'mình' }}</strong></span>
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
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
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
  documents: {
    type: Array,
    default: () => []
  },
  activeInspectDocId: {
    type: String,
    default: null
  },
  isConnected: {
    type: Boolean,
    default: true
  }
})

const emit = defineEmits([
  'teach-ai',
  'open-playbook',
  'toast',
  'select-inspect-doc',
  'view-doc-detail',
  'delete-doc'
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
const isDeliveringBotBubbles = ref(false)
const inboxRemainingDelaySec = ref(0)
let inboxBubbleDeliveryGen = 0
const sleepInbox = (ms) => new Promise(resolve => setTimeout(resolve, ms))

const cancelPendingInboxBubbleDelivery = () => {
  inboxBubbleDeliveryGen += 1
  isDeliveringBotBubbles.value = false
  inboxRemainingDelaySec.value = 0
}
const isUpdatingControl = ref(false)
const isExtractingRule = ref(false)
const lastExtractionResult = ref(null)
const showLeftListPanel = ref(true)
const showInsightPanel = ref(typeof window !== 'undefined' ? window.innerWidth > 1400 : false)
const expandedAiDetailMap = reactive({})

const getAiDetailKey = (msg, idx) => String(msg?.id || `msg-${idx}`)
const isAiDetailExpanded = (msg, idx) => Boolean(expandedAiDetailMap[getAiDetailKey(msg, idx)])
const toggleAiDetail = (msg, idx) => {
  const key = getAiDetailKey(msg, idx)
  expandedAiDetailMap[key] = !expandedAiDetailMap[key]
}

const getMsgActivatedSkills = (msg) => {
  if (!msg) return []
  const list = msg.activated_skills ?? msg.active_skills
  return Array.isArray(list) ? list : []
}

const getMsgAppliedRules = (msg) => {
  if (!msg) return []
  const list = msg.applied_rules ?? msg.appliedRules
  return Array.isArray(list) ? list : []
}

const getMsgAppliedRulesCount = (msg) => {
  const rules = getMsgAppliedRules(msg)
  if (rules.length > 0) return rules.length
  if (typeof msg?.applied_rules_count === 'number') return msg.applied_rules_count
  return 0
}

const formatTurnDurationSec = (msg) => {
  const rawMs = msg?.turn_profiling?.total_turn_ms
    ?? msg?.turn_profiling?.totalTurnMs
    ?? msg?.latency_ms
    ?? 0
  const ms = Number(rawMs) || 0
  if (ms <= 0) return '0.0'
  return (ms / 1000).toFixed(1)
}

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
const isImeComposing = ref(false)
const messagesScrollRef = ref(null)

const handleImeCompositionEnd = (event) => {
  isImeComposing.value = false
  if (event && event.target && typeof event.target.value === 'string') {
    composerText.value = event.target.value
  }
}

const handleComposerEnter = (event) => {
  if (!event) return
  event.preventDefault()
  const domVal = (event.target && typeof event.target.value === 'string')
    ? event.target.value
    : composerText.value
  if (event.isComposing || event.keyCode === 229 || isImeComposing.value) {
    setTimeout(() => {
      if (event.target && typeof event.target.value === 'string') {
        composerText.value = event.target.value
      }
      handleSendComposerMessage(false)
    }, 35)
    return
  }
  composerText.value = domVal
  handleSendComposerMessage(false)
}

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
  cancelPendingInboxBubbleDelivery()
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

const normalizeStructuredReplyBubbles = (botMsg, responseRoot) => {
  const rawArr = (botMsg && Array.isArray(botMsg.reply_bubbles) && botMsg.reply_bubbles.length > 0)
    ? botMsg.reply_bubbles
    : (responseRoot && Array.isArray(responseRoot.reply_bubbles) ? responseRoot.reply_bubbles : [])
  const fallbackDelays = (botMsg && Array.isArray(botMsg.bubble_delays_ms))
    ? botMsg.bubble_delays_ms
    : (responseRoot && Array.isArray(responseRoot.bubble_delays_ms) ? responseRoot.bubble_delays_ms : [])

  if (rawArr.length > 0) {
    const mapped = rawArr
      .map((item, idx) => {
        if (item && typeof item === 'object') {
          return {
            text: String(item.text || '').trim(),
            typing_delay_ms: Math.max(0, Number(item.typing_delay_ms ?? fallbackDelays[idx] ?? 0))
          }
        }
        return {
          text: String(item || '').trim(),
          typing_delay_ms: Math.max(0, Number(fallbackDelays[idx] ?? 0))
        }
      })
      .filter(b => b.text.length > 0)
    // Chống tách kép: Chỉ fallback tách \n{2,} cho bản ghi lịch sử cũ bị gộp trong 1 phần tử duy nhất
    if (mapped.length === 1 && /\r?\n\s*\r?\n+/.test(mapped[0].text)) {
      const legacyParts = mapped[0].text.split(/\r?\n\s*\r?\n+/).map(s => s.trim()).filter(Boolean)
      if (legacyParts.length > 1) {
        return legacyParts.map((text, idx) => ({
          text,
          typing_delay_ms: idx === 0 ? mapped[0].typing_delay_ms : 2000
        }))
      }
    }
    return mapped
  }
  if (botMsg && botMsg.content) {
    return String(botMsg.content)
      .split(/\r?\n\s*\r?\n+/)
      .map(s => s.trim())
      .filter(Boolean)
      .map((text, idx) => ({ text, typing_delay_ms: idx === 0 ? 0 : 2000 }))
  }
  return []
}

const applySessionResponseWithSequentialBubbles = async (res) => {
  if (!res || !res.session) return
  activeSession.value = res.session
  const incomingMsgs = Array.isArray(res.messages) ? res.messages : []
  const lastMsg = incomingMsgs.length > 0 ? incomingMsgs[incomingMsgs.length - 1] : null

  if (!lastMsg || lastMsg.sender_role !== 'AI_BOT' || res.bot_paused_by_human_takeover) {
    activeMessages.value = incomingMsgs
    await scrollToBottom()
    return
  }

  const structuredBubbles = normalizeStructuredReplyBubbles(lastMsg, res)
  if (structuredBubbles.length === 0) {
    activeMessages.value = incomingMsgs
    await scrollToBottom()
    return
  }

  const myGen = ++inboxBubbleDeliveryGen
  const priorMsgs = incomingMsgs.slice(0, incomingMsgs.length - 1)
  const liveBotMsg = reactive({
    ...lastMsg,
    _visible_bubbles: [],
    _delivering_in_progress: true
  })
  activeMessages.value = [...priorMsgs]
  await scrollToBottom()

  try {
    for (let i = 0; i < structuredBubbles.length; i++) {
      if (myGen !== inboxBubbleDeliveryGen) return
      const rawDelay = Math.max(0, Number(structuredBubbles[i].typing_delay_ms ?? 0))
      if (rawDelay > 0) {
        isDeliveringBotBubbles.value = true
        await scrollToBottom()
        if (rawDelay >= 2000) {
          let remainingMs = rawDelay
          while (remainingMs > 0) {
            if (myGen !== inboxBubbleDeliveryGen) return
            inboxRemainingDelaySec.value = Math.ceil(remainingMs / 1000)
            await scrollToBottom()
            const stepMs = Math.min(1000, remainingMs)
            await sleepInbox(stepMs)
            remainingMs -= stepMs
          }
          inboxRemainingDelaySec.value = 0
        } else {
          inboxRemainingDelaySec.value = 0
          await sleepInbox(rawDelay)
        }
      }
      if (myGen !== inboxBubbleDeliveryGen) return
      isDeliveringBotBubbles.value = false
      inboxRemainingDelaySec.value = 0
      if (!activeMessages.value.includes(liveBotMsg)) {
        activeMessages.value.push(liveBotMsg)
      }
      liveBotMsg._visible_bubbles.push(structuredBubbles[i].text)
      await scrollToBottom()
    }
    if (myGen === inboxBubbleDeliveryGen) {
      liveBotMsg._delivering_in_progress = false
      delete liveBotMsg._visible_bubbles
    }
  } finally {
    if (myGen === inboxBubbleDeliveryGen) {
      isDeliveringBotBubbles.value = false
      inboxRemainingDelaySec.value = 0
    }
  }
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
    newCustName.value = ''
    newCustMessage.value = ''
    showNewCustomerForm.value = false
    emit('toast', { message: `💬 Đã tạo cuộc trò chuyện mới với "${custName}"`, type: 'success' })
    if (res && res.session) {
      const list = await api.getConversations(props.activePageId, 'ALL')
      conversations.value = Array.isArray(list) ? list : []
      selectedSessionId.value = res.session.id
      mobilePane.value = 'chat'
      isCreatingConv.value = false
      await applySessionResponseWithSequentialBubbles(res)
    }
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
  cancelPendingInboxBubbleDelivery()
  isSendingMessage.value = true

  try {
    if (composerMode.value === 'HUMAN_SALE') {
      const res = await api.sendHumanSaleReplyInConversation(activeSession.value.id, {
        message: text,
        markConverted
      })
      isSendingMessage.value = false
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
      // Hiển thị tức thì bong bóng của khách trong lúc chờ LLM phản hồi
      activeMessages.value.push({
        id: `optimistic-cust-${Date.now()}`,
        session_id: activeSession.value.id,
        sender_role: 'CUSTOMER',
        sender_name: activeSession.value.customer_name,
        content: text,
        created_at: new Date().toISOString()
      })
      await scrollToBottom()

      const res = await api.sendCustomerTurnInConversation(activeSession.value.id, {
        message: text,
        customerName: activeSession.value.customer_name
      })
      // Phản hồi HTTP đã về xong -> mở khóa sending ngay và thực thi tuần tự typing_delay_ms cho từng bong bóng
      isSendingMessage.value = false
      if (res && res.bot_paused_by_human_takeover) {
        emit('toast', {
          message: '👤 Khách vừa nhắn tin mới. Vì Sale đang tiếp nhận nên AI không tự động chen ngang.',
          type: 'info'
        })
      }
      // Sync list item trước khi chờ typing_delay_ms giữa các bong bóng
      if (res && res.session) {
        const idx = conversations.value.findIndex(c => c.id === res.session.id)
        if (idx !== -1) {
          conversations.value[idx] = { ...conversations.value[idx], ...res.session }
        }
      }
      await applySessionResponseWithSequentialBubbles(res)
      return
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

// Formatting Helpers (No double-splitting on normalized reply_bubbles; preserve single \n inside bubble)
const getMessageBubbles = (msg) => {
  if (!msg) return ['']
  if (Array.isArray(msg._visible_bubbles)) {
    return msg._visible_bubbles
  }
  if (Array.isArray(msg.reply_bubbles) && msg.reply_bubbles.length > 0) {
    const clean = msg.reply_bubbles
      .map(b => (b && typeof b === 'object' ? String(b.text || '') : String(b || '')).trim())
      .filter(Boolean)
    if (clean.length === 1 && /\r?\n\s*\r?\n+/.test(clean[0])) {
      const legacyParts = clean[0].split(/\r?\n\s*\r?\n+/).map(s => s.trim()).filter(Boolean)
      if (legacyParts.length > 1) return legacyParts
    }
    if (clean.length > 0) return clean
  }
  if (msg.sender_role === 'AI_BOT' && msg.content) {
    const parts = String(msg.content).split(/\r?\n\s*\r?\n+/).map(s => s.trim()).filter(Boolean)
    if (parts.length > 1) return parts
  }
  return [msg.content || '']
}

// =========================================================================
// DOCUMENT INSPECTOR & CHUNKS / PRICING VIEWER (FOR "📄 TÀI LIỆU PAGE" TAB)
// =========================================================================
const docChunks = ref([])
const isLoadingChunks = ref(false)
const chunksError = ref('')
const chunkFilterMode = ref('ALL') // 'ALL' | 'PRICING'
const chunkSearchQuery = ref('')
let latestChunksReqId = 0

const scopedDocuments = computed(() => {
  const list = Array.isArray(props.documents) ? props.documents : []
  if (!props.activePageId) return list
  if (props.activePageId === '__unassigned__') {
    return list.filter(d => !d.page_id)
  }
  return list.filter(d => d.page_id === props.activePageId)
})

const selectedInspectDoc = computed(() => {
  if (scopedDocuments.value.length === 0) return null
  if (props.activeInspectDocId) {
    const found = scopedDocuments.value.find(d => d.document_id === props.activeInspectDocId)
    if (found) return found
  }
  return scopedDocuments.value[0]
})

const ensureValidInspectedDoc = () => {
  if (scopedDocuments.value.length === 0) {
    if (props.activeInspectDocId !== null) {
      emit('select-inspect-doc', null)
    }
    docChunks.value = []
    return
  }
  const exists = props.activeInspectDocId && scopedDocuments.value.some(d => d.document_id === props.activeInspectDocId)
  if (!exists) {
    emit('select-inspect-doc', scopedDocuments.value[0].document_id)
  }
}

const loadSelectedDocumentChunks = async () => {
  const doc = selectedInspectDoc.value
  if (!doc || !doc.document_id) {
    docChunks.value = []
    chunksError.value = ''
    return
  }
  if (doc.status !== 'READY') {
    docChunks.value = []
    chunksError.value = ''
    return
  }
  const reqId = ++latestChunksReqId
  isLoadingChunks.value = true
  chunksError.value = ''
  try {
    const list = await api.getDocumentChunks(doc.document_id)
    if (reqId === latestChunksReqId) {
      docChunks.value = Array.isArray(list) ? list : []
    }
  } catch (err) {
    if (reqId === latestChunksReqId) {
      chunksError.value = err.message || 'Lỗi tải dữ liệu chunks'
      docChunks.value = []
    }
  } finally {
    if (reqId === latestChunksReqId) {
      isLoadingChunks.value = false
    }
  }
}

const pricingOrTableChunksCount = computed(() => {
  return docChunks.value.filter(c => c.has_pricing || c.has_table).length
})

const filteredDocChunks = computed(() => {
  return docChunks.value.filter(c => {
    if (chunkFilterMode.value === 'PRICING' && !(c.has_pricing || c.has_table)) {
      return false
    }
    if (chunkSearchQuery.value.trim()) {
      const q = chunkSearchQuery.value.trim().toLowerCase()
      const heading = Array.isArray(c.heading_path) ? c.heading_path.join(' ') : ''
      const hay = `${heading} ${c.text || ''}`.toLowerCase()
      if (!hay.includes(q)) return false
    }
    return true
  })
})

const getDocExtension = (filename) => {
  const parts = String(filename || '').split('.')
  return parts.length > 1 ? parts.pop().toUpperCase().slice(0, 4) : 'DOC'
}

const getChunkMetaArray = (chunk, key) => {
  const val = chunk?.metadata?.[key]
  return Array.isArray(val) ? val : []
}

const hasChunkExtractedMeta = (chunk) => {
  return getChunkMetaArray(chunk, 'match_codes').length > 0 || getChunkMetaArray(chunk, 'match_prices').length > 0
}

const formatVndChip = (val) => {
  const num = Number(val)
  if (!Number.isFinite(num) || num <= 0) return String(val)
  return num.toLocaleString('vi-VN') + 'đ'
}

const isMarkdownTableSeparator = (line) => {
  const trimmed = line.trim()
  if (!trimmed.includes('|') || !trimmed.includes('-')) return false
  return /^\|?[\s:|-]+\|?$/.test(trimmed)
}

const splitMarkdownTableRow = (line) => {
  let trimmed = line.trim()
  if (trimmed.startsWith('|')) trimmed = trimmed.slice(1)
  if (trimmed.endsWith('|')) trimmed = trimmed.slice(0, -1)
  return trimmed.split('|').map(cell => cell.trim().replace(/\*\*/g, ''))
}

const cleanInlineMarkdown = (str) => {
  return String(str || '').replace(/\*\*(.*?)\*\*/g, '$1').trim()
}

const parseChunkBlocks = (rawText) => {
  if (!rawText) return []
  const lines = String(rawText).split(/\r?\n/)
  const blocks = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]
    const trimmed = line.trim()

    if (!trimmed) {
      i++
      continue
    }

    // 1. Markdown Table Detection
    if (
      trimmed.includes('|') &&
      i + 1 < lines.length &&
      isMarkdownTableSeparator(lines[i + 1])
    ) {
      const headers = splitMarkdownTableRow(trimmed)
      i += 2
      const rows = []
      while (i < lines.length && lines[i].trim().includes('|')) {
        if (!isMarkdownTableSeparator(lines[i])) {
          rows.push(splitMarkdownTableRow(lines[i]))
        }
        i++
      }
      blocks.push({ type: 'table', headers, rows })
      continue
    }

    // 2. Markdown Heading
    const headingMatch = trimmed.match(/^(#{1,4})\s+(.+)$/)
    if (headingMatch) {
      blocks.push({
        type: 'heading',
        level: headingMatch[1].length,
        text: cleanInlineMarkdown(headingMatch[2])
      })
      i++
      continue
    }

    // 3. Bullet List
    if (/^[-*+]\s+/.test(trimmed)) {
      const items = []
      while (i < lines.length && /^[-*+]\s+/.test(lines[i].trim())) {
        items.push(cleanInlineMarkdown(lines[i].trim().replace(/^[-*+]\s+/, '')))
        i++
      }
      blocks.push({ type: 'list', items })
      continue
    }

    // 4. Normal Paragraph
    const paraLines = []
    while (
      i < lines.length &&
      lines[i].trim() &&
      !/^(#{1,4})\s+/.test(lines[i].trim()) &&
      !/^[-*+]\s+/.test(lines[i].trim()) &&
      !(lines[i].trim().includes('|') && i + 1 < lines.length && isMarkdownTableSeparator(lines[i + 1]))
    ) {
      paraLines.push(cleanInlineMarkdown(lines[i].trim()))
      i++
    }
    if (paraLines.length > 0) {
      blocks.push({ type: 'paragraph', text: paraLines.join(' ') })
    }
  }

  return blocks
}

watch(leftTab, (newTab) => {
  if (newTab === 'documents') {
    ensureValidInspectedDoc()
    loadSelectedDocumentChunks()
  }
})

watch(
  () => [selectedInspectDoc.value?.document_id, selectedInspectDoc.value?.status],
  () => {
    chunkSearchQuery.value = ''
    chunkFilterMode.value = 'ALL'
    if (leftTab.value === 'documents') {
      loadSelectedDocumentChunks()
    }
  }
)

watch(scopedDocuments, () => {
  if (leftTab.value === 'documents') {
    ensureValidInspectedDoc()
  }
})

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

onBeforeUnmount(() => {
  cancelPendingInboxBubbleDelivery()
})

defineExpose({
  refreshInbox: () => loadConversations(true),
  openDocInspectorOnMobile: () => {
    if (typeof window !== 'undefined' && window.innerWidth <= 1024) {
      mobilePane.value = 'chat'
    }
  }
})
</script>

<style scoped>
.omnichannel-inbox-layout {
  display: flex;
  flex: 1;
  width: 100%;
  height: 100%;
  min-height: 0;
  gap: 10px;
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
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.documents-tab-body :deep(.sidebar-container),
.documents-tab-body :deep(.sidebar) {
  width: 100% !important;
  min-width: 0 !important;
  max-width: 100% !important;
  border: none !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  overflow-x: hidden !important;
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
/* CENTER COLUMN: MESSENGER CHAT THREAD (FULL-VIEWPORT LAPTOP FLEX LAYOUT)   */
/* ========================================================================= */
.inbox-center-thread {
  flex: 1;
  min-width: 0;
  min-height: 0;
  height: 100%;
  background: #ffffff;
  border: 1px solid #dde3ea;
  border-radius: 18px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.03);
}

.thread-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  min-height: 44px;
  background: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  gap: 10px;
  flex-wrap: nowrap;
  flex-shrink: 0;
}

.thread-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.btn-toggle-left-panel {
  padding: 4px 9px;
  border-radius: 999px;
  border: 1px solid #cbd5e1;
  background: #f8fafc;
  color: #334155;
  font-size: 11.5px;
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
  transition: all 0.15s ease;
}

.btn-toggle-left-panel:hover,
.btn-toggle-left-panel.active {
  background: #e8f0fe;
  color: #0b57d0;
  border-color: #a8c7fa;
}

.thread-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  font-weight: 800;
  font-size: 12.5px;
  flex-shrink: 0;
}

.thread-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: nowrap;
  min-width: 0;
  overflow: hidden;
}

.thread-cust-name {
  font-size: 14px;
  font-weight: 800;
  color: #0f172a;
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.thread-honorific-pill,
.thread-stage-pill {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #334155;
  white-space: nowrap;
  flex-shrink: 0;
}

.thread-stage-pill {
  background: #e8f0fe;
  color: #0b57d0;
  font-weight: 700;
}

.mode-indicator-text {
  font-size: 11.5px;
  white-space: nowrap;
  flex-shrink: 0;
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
  gap: 6px;
  flex-wrap: nowrap;
  flex-shrink: 0;
}

.btn-control-mode {
  padding: 5px 10px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 700;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s ease;
  white-space: nowrap;
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

/* Messages Stream (>= 80% Laptop Viewport Height, 6-8+ turns visible continuously) */
.thread-messages-stream {
  flex: 1 1 0%;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 16px;
  background: #f8fafd;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.msg-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
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
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
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
  gap: 8px;
}

.msg-ai-bot .msg-bubble-col,
.msg-human-sale .msg-bubble-col {
  align-items: flex-end;
}

.msg-meta-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
  line-height: 1.2;
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
  padding: 8px 13px;
  border-radius: 16px;
  font-size: 13.5px;
  line-height: 1.46;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
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
  white-space: pre-line;
}

.msg-para + .msg-para {
  margin-top: 6px;
}

/* 1-Line 24px Compact Metadata Pill under AI Bot Turn */
.ai-msg-footer-bar {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  margin-top: 1px;
}

.ai-compact-pill-row {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 24px;
  max-height: 24px;
  white-space: nowrap;
}

.ai-compact-metrics-pill {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  color: #475569;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}

.btn-ai-pill-toggle,
.btn-ai-pill-report {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
  border: 1px solid #cbd5e1;
  background: #ffffff;
  color: #334155;
  transition: all 0.14s ease;
}

.btn-ai-pill-toggle:hover,
.btn-ai-pill-toggle.expanded {
  background: #e8f0fe;
  border-color: #a8c7fa;
  color: #0b57d0;
}

.btn-ai-pill-report:hover {
  border-color: #f59e0b;
  background: #fffbeb;
  color: #b45309;
}

.ai-expanded-detail-drawer {
  background: #ffffff;
  border: 1px solid #dbe4f0;
  border-radius: 10px;
  padding: 7px 10px;
  font-size: 11.5px;
  line-height: 1.45;
  color: #334155;
  max-width: 520px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.06);
}

.ai-detail-line {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.ai-detail-muted {
  color: #64748b;
  font-style: italic;
}

.ai-detail-profiling {
  color: #0b57d0;
  font-family: var(--font-mono, monospace);
  font-size: 11px;
}

/* Compact Single-Row Composer Footer */
.thread-composer-footer {
  padding: 6px 12px 8px;
  background: #ffffff;
  border-top: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.composer-mode-tabs {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.composer-mode-btn {
  height: 34px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 700;
  border: 1px solid #cbd5e1;
  background: #f8fafc;
  color: #475569;
  cursor: pointer;
  white-space: nowrap;
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

.composer-input-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.composer-textarea {
  flex: 1;
  height: 36px;
  min-height: 36px;
  max-height: 76px;
  padding: 7px 12px;
  border-radius: 18px;
  border: 1.5px solid #cbd5e1;
  font-size: 13px;
  line-height: 1.4;
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
  display: inline-flex;
  flex-direction: row;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}

.btn-send-main,
.btn-send-convert {
  height: 34px;
  padding: 0 13px;
  border-radius: 999px;
  border: none;
  font-size: 12px;
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
/* DOCUMENT CONTENT, CHUNKS & PRICING TABLE INSPECTOR (RIGHT PANE)           */
/* ========================================================================= */
.doc-inspector-workspace {
  display: flex;
  flex-direction: column;
  flex: 1;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #ffffff;
}

.doc-inspector-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid #e2e8f0;
  background: #f8fafd;
  flex-wrap: wrap;
}

.doc-inspector-title-group {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex: 1;
  min-width: 0;
}

.doc-inspector-ext-badge {
  font-family: var(--font-mono, monospace);
  font-size: 11px;
  font-weight: 800;
  padding: 6px 9px;
  border-radius: 10px;
  background: #e8f0fe;
  color: #0b57d0;
  border: 1px solid #c2d7f8;
  flex-shrink: 0;
  letter-spacing: 0.04em;
}

.doc-inspector-meta-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.doc-inspector-filename {
  margin: 0;
  font-size: 15px;
  font-weight: 800;
  color: #0f172a;
  line-height: 1.35;
  word-break: break-word;
}

.doc-inspector-pills-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.doc-stat-pill {
  font-size: 11.5px;
  font-weight: 700;
  padding: 2px 9px;
  border-radius: 999px;
  white-space: nowrap;
}

.pill-ready {
  background: #dcfce7;
  color: #166534;
}

.pill-parsing {
  background: #fef9c3;
  color: #854d0e;
}

.pill-failed {
  background: #fee2e2;
  color: #991b1b;
}

.pill-paused {
  background: #f1f5f9;
  color: #475569;
}

.pill-chunks {
  background: #e8f0fe;
  color: #0b57d0;
}

.pill-pricing {
  background: #fef3c7;
  color: #92400e;
  border: 1px solid #fde68a;
}

.doc-inspector-header-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  flex-shrink: 0;
}

.btn-doc-header-act {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 11px;
  border-radius: 999px;
  border: 1px solid #cbd5e1;
  background: #ffffff;
  color: #334155;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.btn-doc-header-act:hover:not(:disabled) {
  background: #f1f5f9;
  border-color: #94a3b8;
  color: #0f172a;
}

.btn-doc-header-del:hover {
  background: #fef2f2;
  border-color: #fca5a5;
  color: #b91c1c;
}

.doc-inspector-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 10px 18px;
  border-bottom: 1px solid #eef2f6;
  background: #ffffff;
}

.doc-chunk-filter-tabs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.chunk-filter-tab {
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  color: #475569;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.chunk-filter-tab:hover {
  background: #f1f5f9;
}

.chunk-filter-tab.active {
  background: #0b57d0;
  border-color: #0b57d0;
  color: #ffffff;
}

.doc-chunk-search-box {
  position: relative;
  flex: 1 1 240px;
  max-width: 380px;
  min-width: 180px;
  display: flex;
  align-items: center;
}

.doc-chunk-search-input {
  width: 100%;
  padding: 7px 28px 7px 12px;
  border-radius: 999px;
  border: 1px solid #cbd5e1;
  background: #f8fafc;
  color: #0f172a;
  font-size: 12.5px;
  outline: none;
}

.doc-chunk-search-input:focus {
  background: #ffffff;
  border-color: #0b57d0;
}

.doc-chunk-search-clear {
  position: absolute;
  right: 8px;
  border: none;
  background: transparent;
  color: #64748b;
  font-size: 15px;
  cursor: pointer;
}

.doc-chunks-scroll {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 14px 18px 80px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: #f8fafc;
}

.doc-chunks-state-box {
  padding: 36px 20px;
  text-align: center;
  background: #ffffff;
  border: 1px dashed #cbd5e1;
  border-radius: 16px;
  color: #475569;
}

.doc-chunks-state-box h4 {
  margin: 0 0 6px;
  font-size: 14.5px;
  color: #0f172a;
}

.doc-chunks-state-box p {
  margin: 0 0 10px;
  font-size: 13px;
}

.doc-chunk-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.03);
  min-width: 0;
}

.doc-chunk-card.has-pricing-highlight {
  border-color: #93c5fd;
  box-shadow: 0 2px 8px rgba(11, 87, 208, 0.06);
}

.chunk-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f1f5f9;
}

.chunk-breadcrumb-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.chunk-idx-badge {
  font-family: var(--font-mono, monospace);
  font-size: 11px;
  font-weight: 800;
  padding: 2px 7px;
  border-radius: 6px;
  background: #0f172a;
  color: #ffffff;
}

.chunk-page-tag {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #475569;
}

.chunk-heading-path {
  font-size: 12.5px;
  font-weight: 700;
  color: #0b57d0;
  word-break: break-word;
}

.chunk-semantic-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
}

.chunk-tag {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}

.tag-pricing {
  background: #fef3c7;
  color: #92400e;
}

.tag-table {
  background: #dbeafe;
  color: #1e40af;
}

.tag-type {
  background: #f1f5f9;
  color: #334155;
  font-family: var(--font-mono, monospace);
}

.chunk-extracted-meta-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.meta-extract-chip {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 6px;
}

.chip-code {
  background: #ede9fe;
  color: #5b21b6;
}

.chip-price {
  background: #ecfdf5;
  color: #065f46;
  border: 1px solid #a7f3d0;
}

.chunk-content-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  font-size: 13.5px;
  line-height: 1.6;
  color: #1e293b;
}

.chunk-block-heading {
  margin: 2px 0 0;
  font-size: 14px;
  font-weight: 800;
  color: #0f172a;
}

.chunk-block-para {
  margin: 0;
  word-break: break-word;
}

.chunk-block-list {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.chunk-table-wrap {
  width: 100%;
  overflow-x: auto;
  border: 1px solid #cbd5e1;
  border-radius: 10px;
  background: #ffffff;
}

.chunk-md-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
  min-width: 480px;
}

.chunk-md-table th {
  background: #eff6ff;
  color: #1e3a8a;
  font-weight: 800;
  text-align: left;
  padding: 8px 11px;
  border-bottom: 1.5px solid #bfdbfe;
  white-space: nowrap;
}

.chunk-md-table td {
  padding: 8px 11px;
  border-bottom: 1px solid #f1f5f9;
  color: #1e293b;
  vertical-align: top;
}

.chunk-md-table tbody tr:last-child td {
  border-bottom: none;
}

.chunk-md-table tbody tr:nth-child(even) {
  background: #f8fafd;
}

/* ========================================================================= */
/* MOBILE NAVIGATION & RESPONSIVE BREAKPOINTS (<= 1280px, <= 1024px, <= 640px) */
/* ========================================================================= */
.mobile-inbox-tabs {
  display: none;
}

.btn-mobile-back-list,
.btn-mobile-back-chat {
  display: none;
}

@media (min-width: 1025px) {
  .inbox-left-panel.is-collapsed-desktop {
    display: none !important;
  }
}

@media (max-width: 1280px) {
  .inbox-left-panel {
    width: 300px;
    min-width: 275px;
    max-width: 320px;
  }

  .inbox-right-insight {
    width: 280px;
    min-width: 250px;
    max-width: 300px;
  }
}

@media (max-width: 1024px) {
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

  .btn-toggle-left-panel {
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
  .doc-chunk-search-input,
  .new-cust-input,
  .new-cust-textarea,
  .composer-textarea {
    font-size: 16px !important;
  }

  .doc-inspector-header,
  .doc-inspector-toolbar,
  .doc-chunks-scroll {
    padding-left: 12px;
    padding-right: 12px;
  }

  .doc-chunk-search-box {
    max-width: 100%;
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
