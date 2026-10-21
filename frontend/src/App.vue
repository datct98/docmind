<template>
  <div class="app-shell">
    <!-- Notification Toast -->
    <transition name="toast">
      <div v-if="toastMessage" class="toast-banner" :class="toastType">
        <span>{{ toastMessage }}</span>
        <button class="toast-close" @click="toastMessage = ''">×</button>
      </div>
    </transition>

    <!-- ===================================================================== -->
    <!-- TOP HEADER BAR (NotebookLM Style - Adaptive for Hub & Workspace)       -->
    <!-- ===================================================================== -->
    <header class="notebook-topbar">
      <div class="topbar-left">
        <!-- Back to Hub Button (Visible in Workspace View) -->
        <button
          v-if="currentView === 'workspace'"
          class="btn-back-hub"
          title="Quay lại màn hình Danh sách Fanpage"
          @click="goToHub"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="m15 18-6-6 6-6"/>
          </svg>
          <span>Danh sách Fanpage</span>
        </button>

        <!-- Brand Identity (Clicking returns to Hub) -->
        <div class="brand-identity" @click="goToHub" title="Về trang chủ Danh sách Fanpage">
          <div class="brand-logo-notebook">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z"/>
              <path d="M8 7h6"/>
              <path d="M8 11h8"/>
              <path d="M8 15h5"/>
            </svg>
          </div>
          <div class="brand-text-col">
            <span class="brand-name">DocMind</span>
            <span class="brand-badge-sub">Sales NotebookLM</span>
          </div>
        </div>

        <!-- Active Page Breadcrumb & Quick Switcher (In Workspace View) -->
        <template v-if="currentView === 'workspace'">
          <span class="topbar-divider">/</span>

          <div class="workspace-page-switcher">
            <span class="page-dot-indicator" :class="activePage ? 'is-scoped' : 'is-global'"></span>
            <select
              class="topbar-page-select"
              :value="activePageId || ''"
              aria-label="Chuyển đổi nhanh Fanpage"
              @change="handleSelectPage($event.target.value || null)"
            >
              <option v-for="p in pages" :key="p.id" :value="p.id">
                📂 {{ p.name }}
              </option>
              <option value="">🌐 Kho Tri thức Chung (Tất cả Page)</option>
              <option v-if="unassignedDocCount > 0" value="__unassigned__">
                🗂️ Tài liệu chưa phân loại ({{ unassignedDocCount }})
              </option>
            </select>
          </div>

          <span v-if="activePage && activePage.industry" class="topbar-industry-pill">
            🏷️ {{ activePage.industry }}
          </span>
          <span v-if="activePage && activePage.target_audience" class="topbar-audience-pill" :title="'Tệp khách hàng: ' + activePage.target_audience">
            👥 {{ activePage.target_audience }}
          </span>

          <button
            v-if="activePage"
            class="btn-config-page"
            title="Cấu hình thông tin & Giọng văn Persona của Fanpage này"
            @click="openEditPageModal(activePage)"
          >
            ⚙️ Cấu hình Page
          </button>
        </template>
      </div>

      <div class="topbar-right">
        <!-- Agent Skill Registry Button -->
        <button
          class="btn btn-secondary btn-sm topbar-action-btn"
          title="Mở Kho Kỹ năng Chuyên gia AI (Tâm lý học, Chốt Sale & Giao tiếp)"
          @click="showSkillModal = true"
        >
          <span>🎯 Kỹ năng Sale</span>
        </button>

        <!-- AI Playbook Button -->
        <button
          class="btn btn-secondary btn-sm topbar-action-btn"
          title="Mở Bộ nhớ Kinh nghiệm AI (AI Playbook)"
          @click="showPlaybookModal = true"
        >
          <span>🧠 Bộ nhớ AI</span>
          <span v-if="activeRulesCount > 0" class="topbar-count-pill">{{ activeRulesCount }}</span>
        </button>

        <!-- Create New Page Button (Prominent in Hub View) -->
        <button
          v-if="currentView === 'hub'"
          class="btn btn-primary btn-sm"
          @click="openCreatePageModal"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <line x1="12" y1="5" x2="12" y2="19"/>
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
          <span>Tạo Fanpage mới</span>
        </button>

        <!-- LLM & API Key Healthcheck Pill -->
        <button
          class="llm-health-pill"
          :class="llmHealthPillClass"
          :title="llmHealth.status_message || 'Bấm để kiểm tra trạng thái API Key & Model LLM'"
          @click="openLlmHealthModal"
        >
          <span class="llm-health-dot" :class="{ 'is-pulsing': isPingingLlm }"></span>
          <span class="llm-health-label">
            🔑 {{ llmHealth.provider ? llmHealth.provider.toUpperCase() : 'LLM' }}:
            <strong>{{ llmHealth.model || 'Chưa rõ' }}</strong>
          </span>
          <span v-if="isPingingLlm" class="llm-health-badge">Đang ping...</span>
          <span v-else-if="llmHealth.latency_ms != null" class="llm-health-badge">
            {{ llmHealth.latency_ms }}ms
          </span>
          <span v-else-if="llmHealth.api_key_masked" class="llm-health-badge">
            {{ llmHealth.api_key_masked }}
          </span>
        </button>
      </div>
    </header>

    <!-- ===================================================================== -->
    <!-- VIEW 1: NOTEBOOKLM-STYLE FANPAGE HUB / DIRECTORY SCREEN               -->
    <!-- ===================================================================== -->
    <main v-if="currentView === 'hub'" class="hub-view-container">
      <div class="hub-inner">
        <!-- Hero Banner Section -->
        <section class="hub-hero">
          <div class="hub-hero-text">
            <h1 class="hub-title">Không gian Tri thức Fanpage</h1>
            <p class="hub-subtitle">
              Mỗi Fanpage hoạt động như một <strong>Notebook độc lập</strong> với kho tài liệu nguồn, giọng văn Persona, kỹ năng chốt sale và bộ nhớ quy tắc AI riêng. Chọn một Fanpage bên dưới để mở không gian làm việc.
            </p>
          </div>

          <div class="hub-summary-pills">
            <div class="summary-stat-pill">
              <span class="stat-num">{{ pages.length }}</span>
              <span class="stat-label">Fanpage đang hoạt động</span>
            </div>
            <div class="summary-stat-pill">
              <span class="stat-num">{{ documents.length }}</span>
              <span class="stat-label">Tài liệu nguồn đã nạp</span>
            </div>
            <div class="summary-stat-pill">
              <span class="stat-num">{{ activeRulesCount }}</span>
              <span class="stat-label">Quy tắc AI Playbook</span>
            </div>
          </div>
        </section>

        <!-- Filter & Search Controls Bar -->
        <section class="hub-toolbar">
          <div class="hub-search-box">
            <svg xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="hub-search-icon">
              <circle cx="11" cy="11" r="8"/>
              <line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <input
              v-model="hubSearchQuery"
              type="text"
              class="hub-search-input"
              placeholder="Tìm Fanpage theo tên, ngành hàng, tệp khách hàng hoặc giọng văn Persona..."
            />
            <button v-if="hubSearchQuery" class="hub-search-clear" @click="hubSearchQuery = ''">×</button>
          </div>

          <div class="hub-filter-group">
            <button
              class="hub-filter-pill"
              :class="{ active: hubIndustryFilter === 'ALL' }"
              @click="hubIndustryFilter = 'ALL'"
            >
              Tất cả ({{ pages.length }})
            </button>
            <button
              v-for="ind in distinctIndustries"
              :key="ind"
              class="hub-filter-pill"
              :class="{ active: hubIndustryFilter === ind }"
              @click="hubIndustryFilter = ind"
            >
              {{ ind }}
            </button>
          </div>
        </section>

        <!-- NotebookLM-Style Cards Grid -->
        <section class="notebooks-grid">
          <!-- 1. Create New Fanpage Card -->
          <div class="notebook-card create-notebook-card" @click="openCreatePageModal">
            <div class="create-card-icon">
              <svg xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"/>
                <line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
            </div>
            <h3 class="create-card-title">Tạo Fanpage mới</h3>
            <p class="create-card-desc">
              Thiết lập kênh tư vấn mới với ngành nghề, tệp khách hàng mục tiêu và kho tài liệu riêng biệt
            </p>
          </div>

          <!-- 2. Individual Fanpage Notebook Cards -->
          <article
            v-for="(page, index) in filteredHubPages"
            :key="page.id"
            class="notebook-card page-notebook-card"
            :class="getNotebookThemeClass(index)"
            @click="openPageWorkspace(page.id)"
          >
            <!-- Card Top Pastel Header -->
            <div class="notebook-card-top">
              <div class="notebook-icon-wrap">
                <span class="notebook-emoji">{{ getIndustryIcon(page.industry, page.name) }}</span>
              </div>

              <div class="notebook-top-badges">
                <span v-if="page.industry" class="notebook-industry-tag">
                  {{ page.industry }}
                </span>
                <span v-else class="notebook-industry-tag">Đa ngành</span>
              </div>

              <div class="notebook-card-menu" @click.stop>
                <button
                  class="card-icon-btn"
                  title="Chỉnh sửa thông tin & Persona của Page"
                  @click="openEditPageModal(page)"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/>
                    <path d="m15 5 4 4"/>
                  </svg>
                </button>
                <button
                  class="card-icon-btn card-icon-del"
                  title="Xóa Page này"
                  @click="confirmDeletePage(page)"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M3 6h18"/>
                    <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/>
                    <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/>
                  </svg>
                </button>
              </div>
            </div>

            <!-- Card Body: Title, Audience, Persona -->
            <div class="notebook-card-body">
              <h3 class="notebook-title" :title="page.name">{{ page.name }}</h3>

              <div v-if="page.target_audience" class="notebook-audience-row">
                <span class="audience-label">👥 Khách hàng:</span>
                <span class="audience-val">{{ page.target_audience }}</span>
              </div>

              <p class="notebook-persona-desc">
                {{ page.description || 'Chưa cấu hình giọng văn riêng — Sử dụng phong cách tư vấn Sale-Ready chuẩn mực.' }}
              </p>
            </div>

            <!-- Card Footer: Live Stats & Direct Actions -->
            <div class="notebook-card-footer">
              <div class="notebook-stats-row">
                <span class="nb-stat-chip" :class="{ 'has-docs': getPageDocCount(page.id) > 0 }">
                  📄 {{ getPageDocCount(page.id) }} tài liệu nguồn
                </span>
                <span class="nb-stat-chip">
                  🧠 {{ getPageRuleCount(page.id) }} quy tắc AI
                </span>
              </div>

              <div class="notebook-cta-row">
                <button
                  class="btn-card-sim"
                  title="Mở không gian Page và bật ngay Giả lập Chat Fanpage"
                  @click.stop="openPageSimulatorDirect(page.id)"
                >
                  💬 Test Chat
                </button>
                <span class="btn-card-open">
                  Mở Page
                  <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M5 12h14"/>
                    <path d="m12 5 7 7-7 7"/>
                  </svg>
                </span>
              </div>
            </div>
          </article>

          <!-- 3. Global Multi-Page Repository Card -->
          <article
            class="notebook-card global-notebook-card"
            @click="openPageWorkspace(null)"
          >
            <div class="notebook-card-top">
              <div class="notebook-icon-wrap global-icon-wrap">
                <span class="notebook-emoji">🌐</span>
              </div>
              <div class="notebook-top-badges">
                <span class="notebook-industry-tag global-tag">Kho Tổng Hợp</span>
              </div>
            </div>

            <div class="notebook-card-body">
              <h3 class="notebook-title">Kho Tri thức Chung (Tất cả Fanpage)</h3>
              <div class="notebook-audience-row">
                <span class="audience-label">📚 Phạm vi:</span>
                <span class="audience-val">Tra cứu chéo toàn bộ {{ documents.length }} tài liệu trên hệ thống</span>
              </div>
              <p class="notebook-persona-desc">
                Không gian tra cứu tổng hợp cho Quản lý & Admin khi cần kiểm tra chéo toàn bộ tài liệu hoặc phân loại các tệp chưa gán Page.
              </p>
            </div>

            <div class="notebook-card-footer">
              <div class="notebook-stats-row">
                <span class="nb-stat-chip has-docs">📄 {{ documents.length }} tổng tài liệu</span>
                <span v-if="unassignedDocCount > 0" class="nb-stat-chip">
                  🗂️ {{ unassignedDocCount }} chưa phân loại
                </span>
              </div>
              <div class="notebook-cta-row">
                <span class="btn-card-open">
                  Mở Kho chung
                  <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M5 12h14"/>
                    <path d="m12 5 7 7-7 7"/>
                  </svg>
                </span>
              </div>
            </div>
          </article>
        </section>
      </div>
    </main>

    <!-- ===================================================================== -->
    <!-- VIEW 2: PAGE WORKSPACE SCREEN (Omnichannel Inbox + Floating Bubbles)  -->
    <!-- ===================================================================== -->
    <div v-else class="main-layout">
      <!-- Primary 3-Column Omnichannel Conversation Inbox (Left: KH List + Docs Tab | Center: Messenger Thread | Right: Customer Insight) -->
      <LiveConversationInbox
        ref="inboxRef"
        :active-page="activePage"
        :active-page-id="activePage ? activePage.id : null"
        :scoped-doc-count="selectedDocuments.length"
        :is-connected="isConnected"
        @teach-ai="openTeachAi"
        @open-playbook="showPlaybookModal = true"
        @toast="showToast($event.message, $event.type || 'info')"
      >
        <template #document-sidebar>
          <DocumentSidebar
            :documents="documents"
            :pages="pages"
            :active-page-id="activePageId"
            :selected-doc-ids="selectedDocIds"
            :is-uploading="isUploading"
            :is-connected="isConnected"
            @upload-file="handleUpload"
            @toggle-select="handleToggleSelect"
            @toggle-select-all="handleToggleSelectAll"
            @refresh-documents="fetchDocuments"
            @view-detail="inspectingDoc = $event"
            @delete-doc="confirmDeleteDoc"
            @open-playbook="showPlaybookModal = true"
            @select-page="handleSelectPage"
            @create-page="handleCreatePage"
            @update-page="handleUpdatePage"
            @delete-page="confirmDeletePage"
            @assign-page="handleAssignDocPage"
            @back-to-hub="goToHub"
          />
        </template>
      </LiveConversationInbox>

      <!-- Floating Internal Q&A Drawer (Minimized into a Side Bubble when not needed) -->
      <div v-show="showQaBubbleDrawer" class="floating-qa-drawer-window">
        <div class="floating-qa-topbar">
          <div class="floating-qa-title">
            <span>📚 Hỏi đáp Tài liệu Nội bộ (RAG Q&A)</span>
            <span class="floating-qa-sub">Tra cứu nhanh bảng giá & quy định cho Sale</span>
          </div>
          <button
            class="btn-minimize-qa"
            title="Thu gọn về bong bóng chat"
            @click="showQaBubbleDrawer = false"
          >
            — Thu gọn
          </button>
        </div>
        <div class="floating-qa-body">
          <ChatArea
            ref="chatAreaRef"
            :selected-docs="selectedDocuments"
            :all-docs="documents"
            :pages="pages"
            :active-page="activePage"
            :is-connected="isConnected"
            :messages="messages"
            :is-asking="isAsking"
            :active-rules-count="activeRulesCount"
            @ask-question="handleAskQuestion"
            @clear-chat="handleClearChat"
            @open-playbook="showPlaybookModal = true"
            @open-skills="showSkillModal = true"
            @teach-ai="openTeachAi"
            @simulator-closed="inboxRef?.refreshInbox()"
          />
        </div>
      </div>

      <!-- Bottom-Right Floating Chat Bubbles Dock (Test Chat Bot + Hỏi đáp Q&A) -->
      <div class="floating-bubbles-dock">
        <button
          class="floating-bubble-pill bubble-qa"
          :class="{ active: showQaBubbleDrawer }"
          title="Bật/Tắt Bong bóng Hỏi đáp Tài liệu Q&A Nội bộ"
          @click="showQaBubbleDrawer = !showQaBubbleDrawer"
        >
          <span class="bubble-icon">📚</span>
          <span class="bubble-label">Hỏi đáp Q&A</span>
          <span v-if="messages.length > 0" class="bubble-count-tag">{{ messages.length }}</span>
        </button>

        <button
          class="floating-bubble-pill bubble-test-bot"
          title="Mở Bong bóng Test Chat Bot Fanpage (Giả lập kịch bản độc lập)"
          @click="openWorkspaceSimulator"
        >
          <span class="bubble-icon">💬</span>
          <span class="bubble-label">Test Chat Bot</span>
          <span class="bubble-live-dot"></span>
        </button>
      </div>
    </div>

    <!-- ===================================================================== -->
    <!-- CREATE / EDIT FANPAGE PERSONA MODAL (Accessible from Hub & Workspace) -->
    <!-- ===================================================================== -->
    <div v-if="showPageModal" class="modal-backdrop" @click.self="closePageModal">
      <div class="modal-card page-persona-modal-card">
        <div class="modal-header">
          <div class="modal-title-group">
            <div class="page-modal-icon">
              {{ editingPageId ? '✏️' : '✨' }}
            </div>
            <div>
              <h3 class="modal-title">
                {{ editingPageId ? 'Cấu hình Không gian Fanpage & Persona' : 'Tạo Không gian Fanpage mới (Notebook)' }}
              </h3>
              <p class="modal-subtitle-text">
                Thiết lập ngành hàng, chân dung khách hàng mục tiêu và giọng văn tư vấn đặc trưng
              </p>
            </div>
          </div>
          <button class="btn-close" @click="closePageModal">×</button>
        </div>

        <div class="modal-body">
          <div class="form-field-group">
            <label class="field-label">Tên Fanpage / Không gian Tri thức <span class="req-star">*</span></label>
            <input
              v-model="pageModalForm.name"
              type="text"
              class="modal-input"
              placeholder="Ví dụ: Page Thẩm Mỹ Viện & Chăm Sóc Da Aura Spa"
            />
          </div>

          <div class="form-row-2col">
            <div class="form-field-group">
              <label class="field-label">Ngành hàng / Lĩnh vực</label>
              <input
                v-model="pageModalForm.industry"
                type="text"
                class="modal-input"
                placeholder="VD: Làm đẹp & Chăm sóc da"
              />
            </div>

            <div class="form-field-group">
              <label class="field-label">Tệp khách hàng mục tiêu</label>
              <input
                v-model="pageModalForm.target_audience"
                type="text"
                class="modal-input"
                placeholder="VD: Nữ 25-45 tuổi, Nhân viên VP"
              />
            </div>
          </div>

          <div class="form-field-group">
            <label class="field-label">🎭 Page Persona & Phong cách Tư vấn Chốt Sale</label>
            <textarea
              v-model="pageModalForm.description"
              rows="3"
              class="modal-input modal-textarea"
              placeholder="VD: Xưng Em - Chị dịu dàng, chuyên nghiệp; luôn nêu quyền lợi & cam kết Y khoa trước khi báo giá, khéo léo mời đặt lịch soi da AI 3D miễn phí..."
            ></textarea>
            <span class="field-helper">
              Chỉ dẫn này sẽ định hình giọng điệu của cả Trợ lý Q&A nội bộ lẫn Bot trực Fanpage tự động.
            </span>
          </div>
        </div>

        <div class="modal-footer">
          <button class="btn btn-secondary" @click="closePageModal">Hủy</button>
          <button
            class="btn btn-primary"
            :disabled="!pageModalForm.name.trim()"
            @click="submitPageModal"
          >
            {{ editingPageId ? 'Lưu cập nhật Fanpage' : 'Tạo Fanpage & Mở Không gian' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Confirm Delete Page Modal -->
    <div v-if="deletingPage" class="modal-backdrop" @click.self="deletingPage = null">
      <div class="modal-card modal-delete-card">
        <div class="modal-header">
          <div class="modal-title-group text-danger">
            <h3 class="modal-title">Xác nhận xóa Fanpage "{{ deletingPage.name }}"</h3>
          </div>
          <button class="btn-close" @click="deletingPage = null">×</button>
        </div>
        <div class="modal-body">
          <p class="delete-warning-text">
            Bạn có chắc chắn muốn xóa không gian Fanpage <strong>"{{ deletingPage.name }}"</strong>?
          </p>
          <div class="danger-box">
            <div class="danger-box-title">Lưu ý an toàn dữ liệu:</div>
            <ul class="danger-box-list">
              <li>Các tài liệu bên trong Page sẽ <strong>không bị mất</strong> mà được chuyển về mục <em>"Chưa phân loại"</em>.</li>
              <li>Bạn có thể gán lại các tài liệu này sang Page khác bất kỳ lúc nào.</li>
            </ul>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-secondary" @click="deletingPage = null">Hủy</button>
          <button class="btn btn-danger" @click="executeDeletePage">Xác nhận xóa Fanpage</button>
        </div>
      </div>
    </div>

    <!-- Document Detail Inspector Modal -->
    <DocumentDetailModal
      v-if="inspectingDoc"
      :doc="inspectingDoc"
      @close="inspectingDoc = null"
      @delete-doc="confirmDeleteDoc"
    />

    <!-- AI Playbook Manager Modal -->
    <PlaybookModal
      v-if="showPlaybookModal"
      :is-connected="isConnected"
      :pages="pages"
      :active-page-id="activePage ? activePage.id : null"
      @close="showPlaybookModal = false"
      @rules-changed="handleRulesChanged"
    />

    <!-- Agent Skill Registry Modal -->
    <AgentSkillModal
      v-if="showSkillModal"
      :demo-mode="false"
      :pages="pages"
      :active-page-id="activePage ? activePage.id : null"
      @close="showSkillModal = false"
    />

    <!-- Teach AI / Báo sai & Dạy AI Modal -->
    <TeachAiModal
      v-if="teachingFeedback"
      :question="teachingFeedback.question"
      :faulty-answer="teachingFeedback.faultyAnswer"
      :document-ids="teachingFeedback.documentIds"
      :message-index="teachingFeedback.messageIndex"
      :applied-rule-ids="teachingFeedback.appliedRuleIds || []"
      :is-connected="isConnected"
      :pages="pages"
      :active-page-id="activePage ? activePage.id : null"
      @close="teachingFeedback = null"
      @submitted="handleTeachSubmitted"
    />

    <!-- Confirm Delete Document Modal -->
    <div v-if="deletingDoc" class="modal-backdrop" @click.self="deletingDoc = null">
      <div class="modal-card modal-delete-card">
        <div class="modal-header">
          <div class="modal-title-group text-danger">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="title-icon-danger">
              <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/>
              <line x1="12" y1="9" x2="12" y2="13"/>
              <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
            <h3 class="modal-title">Xác nhận xóa tài liệu</h3>
          </div>
          <button class="btn-close" @click="deletingDoc = null">×</button>
        </div>

        <div class="modal-body">
          <p class="delete-warning-text">
            Bạn có chắc chắn muốn xóa tài liệu <strong>"{{ deletingDoc.filename }}"</strong>?
          </p>

          <div class="danger-box">
            <div class="danger-box-title">Hành động này sẽ xóa vĩnh viễn trong database:</div>
            <ul class="danger-box-list">
              <li>Thông tin tài liệu (document record & stats).</li>
              <li>Toàn bộ các khối nội dung đã bóc tách (canonical blocks).</li>
              <li>Toàn bộ <strong>vector embeddings (pgvector 384-dim)</strong> và chỉ mục tìm kiếm full-text (BM25 tsvector).</li>
            </ul>
          </div>
        </div>

        <div class="modal-footer">
          <button class="btn btn-secondary" :disabled="isDeleting" @click="deletingDoc = null">
            Hủy
          </button>
          <button class="btn btn-danger" :disabled="isDeleting" @click="executeDelete">
            <span v-if="!isDeleting">Xác nhận xóa vĩnh viễn</span>
            <span v-else>Đang xóa...</span>
          </button>
        </div>
      </div>
    </div>

    <!-- LLM & API Key Healthcheck Inspector Modal -->
    <div v-if="showLlmHealthModal" class="modal-backdrop" @click.self="showLlmHealthModal = false">
      <div class="modal-card modal-llm-health-card">
        <div class="modal-header">
          <div class="modal-title-group">
            <span class="modal-emoji">🔑</span>
            <div>
              <h3 class="modal-title">Healthcheck API Key & Model LLM</h3>
              <p class="modal-subtitle">Kiểm tra cấu hình kết nối, xác thực API Key và đo độ trễ phản hồi trực tiếp</p>
            </div>
          </div>
          <button class="btn-close" @click="showLlmHealthModal = false">×</button>
        </div>

        <div class="modal-body llm-health-modal-body">
          <!-- Status Banner -->
          <div class="llm-status-banner" :class="llmHealthPillClass">
            <div class="llm-status-banner-top">
              <span class="llm-status-icon">
                <template v-if="isPingingLlm">⏳</template>
                <template v-else-if="llmHealth.status === 'HEALTHY'">🟢</template>
                <template v-else-if="llmHealth.status === 'CONFIGURED'">🟡</template>
                <template v-else>🔴</template>
              </span>
              <div class="llm-status-text-wrap">
                <div class="llm-status-code">
                  Trạng thái: <strong>{{ llmHealthStatusLabel }}</strong>
                  <span v-if="llmHealth.http_status" class="llm-http-chip">HTTP {{ llmHealth.http_status }}</span>
                  <span v-if="llmHealth.latency_ms != null" class="llm-latency-chip">⚡ {{ llmHealth.latency_ms }} ms</span>
                </div>
                <div class="llm-status-desc">{{ llmHealth.status_message || 'Đang kiểm tra cấu hình...' }}</div>
              </div>
            </div>
          </div>

          <!-- Configuration Details Grid -->
          <div class="llm-info-grid">
            <div class="llm-info-item">
              <span class="llm-info-label">Nhà cung cấp (Provider)</span>
              <span class="llm-info-value">{{ (llmHealth.provider || 'N/A').toUpperCase() }}</span>
            </div>
            <div class="llm-info-item">
              <span class="llm-info-label">Model đang hoạt động</span>
              <span class="llm-info-value code-font">{{ llmHealth.model || 'N/A' }}</span>
            </div>
            <div class="llm-info-item">
              <span class="llm-info-label">API Key (Che bảo mật)</span>
              <span class="llm-info-value code-font">
                {{ llmHealth.api_key_masked || 'Chưa cấu hình' }}
                <span
                  class="key-status-tag"
                  :class="llmHealth.api_key_configured ? 'tag-ok' : 'tag-missing'"
                >
                  {{ llmHealth.api_key_configured ? 'Đã nạp Key' : 'Thiếu Key' }}
                </span>
              </span>
            </div>
            <div class="llm-info-item">
              <span class="llm-info-label">Tham số suy luận</span>
              <span class="llm-info-value code-font">
                effort={{ llmHealth.reasoning_effort || 'low' }} · temp={{ llmHealth.temperature ?? 0.2 }} · timeout={{ llmHealth.timeout_seconds ?? 120 }}s
              </span>
            </div>
            <div class="llm-info-item llm-info-full">
              <span class="llm-info-label">Base URL / Endpoint thực tế</span>
              <span class="llm-info-value code-font">{{ llmHealth.endpoint || llmHealth.base_url || 'N/A' }}</span>
            </div>
          </div>

          <!-- Live Ping Response / Error Box -->
          <div v-if="llmHealth.sample_reply || llmHealth.error_detail" class="llm-ping-output-box">
            <div class="llm-ping-output-title">
              {{ llmHealth.error_detail ? '⚠️ Chi tiết lỗi từ Endpoint:' : '💬 Phản hồi thực tế từ Model khi Ping:' }}
              <span v-if="llmHealth.checked_at" class="llm-checked-time">Lúc: {{ formatCheckedAt(llmHealth.checked_at) }}</span>
            </div>
            <pre class="llm-ping-pre" :class="{ 'is-error': !!llmHealth.error_detail }">{{ llmHealth.error_detail || llmHealth.sample_reply }}</pre>
          </div>

          <div class="llm-env-hint">
            💡 <strong>Mẹo cấu hình nhanh:</strong> Các thông số trên được nạp từ file <code>docmind/.env</code> (<code>LLM_PROVIDER</code>, <code>LLM_MODEL</code>, <code>LLM_BASE_URL</code>, <code>LLM_API_KEY</code>).
          </div>
        </div>

        <div class="modal-footer">
          <button class="btn btn-secondary" @click="showLlmHealthModal = false">
            Đóng
          </button>
          <button class="btn btn-primary" :disabled="isPingingLlm" @click="runLlmLivePing">
            <span v-if="!isPingingLlm">⚡ Test Kết Nối & API Key Ngay (Live Ping)</span>
            <span v-else>⏳ Đang gửi gói tin kiểm tra tới {{ llmHealth.model || 'LLM' }}...</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { api } from './api'
import DocumentSidebar from './components/DocumentSidebar.vue'
import ChatArea from './components/ChatArea.vue'
import LiveConversationInbox from './components/LiveConversationInbox.vue'
import DocumentDetailModal from './components/DocumentDetailModal.vue'
import PlaybookModal from './components/PlaybookModal.vue'
import AgentSkillModal from './components/AgentSkillModal.vue'
import TeachAiModal from './components/TeachAiModal.vue'

// View navigation state: 'hub' (Page Directory Home) | 'workspace' (Active Page Workspace)
const currentView = ref('hub')
const hubSearchQuery = ref('')
const hubIndustryFilter = ref('ALL')

const documents = ref([])
const pages = ref([])
const activePageId = ref(null)
const selectedDocIds = ref([])
const messages = ref([])
const isUploading = ref(false)
const isAsking = ref(false)
const isConnected = ref(true)
const inspectingDoc = ref(null)
const deletingDoc = ref(null)
const deletingPage = ref(null)
const isDeleting = ref(false)
const toastMessage = ref('')
const toastType = ref('info')
const chatAreaRef = ref(null)
const inboxRef = ref(null)
const showQaBubbleDrawer = ref(false)

const showPlaybookModal = ref(false)
const showSkillModal = ref(false)
const teachingFeedback = ref(null)
const activeRulesCount = ref(0)

// LLM & API Key Healthcheck state
const showLlmHealthModal = ref(false)
const isPingingLlm = ref(false)
const llmHealth = ref({
  provider: 'grok',
  model: 'grok-4.5',
  base_url: 'https://gpt.teamsoclo.site/v1',
  endpoint: 'https://gpt.teamsoclo.site/v1/chat/completions',
  api_key_configured: true,
  api_key_masked: 'sk-Ywtd...2iic',
  reasoning_effort: 'low',
  temperature: 0.2,
  timeout_seconds: 120,
  status: 'CONFIGURED',
  healthy: true,
  status_message: 'Bấm để kiểm tra trực tiếp kết nối API Key & Model LLM.',
  http_status: null,
  latency_ms: null,
  sample_reply: null,
  error_detail: null,
  checked_at: null
})

const llmHealthPillClass = computed(() => {
  if (isPingingLlm.value) return 'status-checking'
  const st = llmHealth.value?.status
  if (st === 'HEALTHY') return 'status-healthy'
  if (st === 'CONFIGURED') return 'status-configured'
  return 'status-error'
})

const llmHealthStatusLabel = computed(() => {
  if (isPingingLlm.value) return 'ĐANG KIỂM TRA...'
  const map = {
    HEALTHY: 'HOẠT ĐỘNG TỐT (HEALTHY)',
    CONFIGURED: 'ĐÃ CẤU HÌNH (SẴN SÀNG)',
    MISSING_KEY: 'CHƯA CẤU HÌNH API KEY',
    INVALID_KEY: 'API KEY KHÔNG HỢP LỆ / HẾT HẠN',
    QUOTA_EXCEEDED: 'HẾT HẠN MỨC / QUOTA (429/402)',
    MODEL_OR_URL_NOT_FOUND: 'SAI TÊN MODEL HOẶC BASE URL (404)',
    ENDPOINT_ERROR: 'LỖI TỪ MÁY CHỦ LLM',
    CONNECTION_ERROR: 'KHÔNG THỂ KẾT NỐI ENDPOINT'
  }
  return map[llmHealth.value?.status] || llmHealth.value?.status || 'CHƯA RÕ'
})

const formatCheckedAt = (iso) => {
  if (!iso) return ''
  try {
    return new Date(iso).toLocaleTimeString('vi-VN')
  } catch {
    return iso
  }
}

const fetchLlmHealth = async (ping = false) => {
  if (ping) isPingingLlm.value = true
  try {
    const data = await api.getLlmHealth(ping)
    if (data) {
      llmHealth.value = { ...llmHealth.value, ...data }
    }
  } catch (err) {
    console.warn('LLM health check error:', err)
  } finally {
    if (ping) isPingingLlm.value = false
  }
}

const openLlmHealthModal = () => {
  showLlmHealthModal.value = true
  if (llmHealth.value.latency_ms == null && !isPingingLlm.value) {
    runLlmLivePing()
  } else {
    fetchLlmHealth(false)
  }
}

const runLlmLivePing = async () => {
  isPingingLlm.value = true
  try {
    const data = await api.pingLlm()
    if (data) {
      llmHealth.value = { ...llmHealth.value, ...data }
      if (data.status === 'HEALTHY') {
        showToast(`✅ API Key & Model ${data.model} hoạt động tốt (${data.latency_ms}ms)!`, 'success')
      } else {
        showToast(`⚠️ Kiểm tra LLM: ${data.status_message || data.status}`, 'error', 5000)
      }
    }
  } catch (err) {
    showToast(`Lỗi khi ping LLM: ${err.message}`, 'error')
  } finally {
    isPingingLlm.value = false
  }
}

// Create / Edit Page Modal state
const showPageModal = ref(false)
const editingPageId = ref(null)
const pageModalForm = ref({
  name: '',
  industry: '',
  target_audience: '',
  description: ''
})

const pollingTimers = new Map()

const activePage = computed(() => {
  if (activePageId.value === '__unassigned__') return null
  if (activePageId.value) {
    return pages.value.find(p => p.id === activePageId.value) || null
  }
  const selected = documents.value.filter(d => selectedDocIds.value.includes(d.document_id) && d.page_id)
  if (selected.length > 0) {
    const firstPageId = selected[0].page_id
    if (selected.every(d => d.page_id === firstPageId)) {
      return pages.value.find(p => p.id === firstPageId) || null
    }
  }
  return null
})

const selectedDocuments = computed(() => {
  return documents.value.filter(d => selectedDocIds.value.includes(d.document_id))
})

const unassignedDocCount = computed(() => {
  return documents.value.filter(d => !d.page_id).length
})

const distinctIndustries = computed(() => {
  const set = new Set()
  pages.value.forEach(p => {
    if (p.industry && p.industry.trim()) {
      set.add(p.industry.trim())
    }
  })
  return Array.from(set)
})

const filteredHubPages = computed(() => {
  return pages.value.filter(p => {
    if (hubIndustryFilter.value !== 'ALL' && (p.industry || '').trim() !== hubIndustryFilter.value) {
      return false
    }
    if (hubSearchQuery.value.trim()) {
      const q = hubSearchQuery.value.trim().toLowerCase()
      const hay = `${p.name || ''} ${p.industry || ''} ${p.target_audience || ''} ${p.description || ''}`.toLowerCase()
      if (!hay.includes(q)) return false
    }
    return true
  })
})

const getPageDocCount = (pageId) => {
  const localCount = documents.value.filter(d => d.page_id === pageId).length
  if (localCount > 0) return localCount
  const pageObj = pages.value.find(p => p.id === pageId)
  return pageObj?.document_count || 0
}

const getPageRuleCount = (pageId) => {
  const pageObj = pages.value.find(p => p.id === pageId)
  if (pageObj && pageObj.rule_count !== undefined) {
    return pageObj.rule_count
  }
  return 0
}

const getNotebookThemeClass = (index) => {
  const themes = ['nb-theme-blue', 'nb-theme-mint', 'nb-theme-amber', 'nb-theme-lavender']
  return themes[index % themes.length]
}

const getIndustryIcon = (industry, name) => {
  const text = `${industry || ''} ${name || ''}`.toLowerCase()
  if (text.includes('spa') || text.includes('làm đẹp') || text.includes('mỹ phẩm') || text.includes('thẩm mỹ')) return '✨'
  if (text.includes('b2b') || text.includes('phần mềm') || text.includes('công nghệ') || text.includes('saas')) return '💻'
  if (text.includes('bất động sản') || text.includes('căn hộ') || text.includes('bđs')) return '🏢'
  if (text.includes('thời trang') || text.includes('quần áo')) return '👗'
  if (text.includes('giáo dục') || text.includes('khóa học')) return '🎓'
  if (text.includes('y tế') || text.includes('nha khoa') || text.includes('phòng khám')) return '🩺'
  return '📘'
}

const showToast = (msg, type = 'info', timeout = 4000) => {
  toastMessage.value = msg
  toastType.value = type
  setTimeout(() => {
    if (toastMessage.value === msg) {
      toastMessage.value = ''
    }
  }, timeout)
}

/**
 * Mở không gian làm việc của 1 Fanpage từ màn hình Hub
 */
const openPageWorkspace = (pageId) => {
  handleSelectPage(pageId)
  currentView.value = 'workspace'
}

/**
 * Mở thẳng không gian làm việc của Fanpage và bật ngay Modal Giả lập Chat Fanpage
 */
const openPageSimulatorDirect = async (pageId) => {
  handleSelectPage(pageId)
  currentView.value = 'workspace'
  await nextTick()
  chatAreaRef.value?.openFanpageSimulator()
}

const openWorkspaceSimulator = () => {
  chatAreaRef.value?.openFanpageSimulator()
}

/**
 * Quay về màn hình Danh sách Fanpage (NotebookLM Home)
 */
const goToHub = () => {
  currentView.value = 'hub'
  fetchInitialRulesCount()
}

const openCreatePageModal = () => {
  editingPageId.value = null
  pageModalForm.value = {
    name: '',
    industry: '',
    target_audience: '',
    description: ''
  }
  showPageModal.value = true
}

const openEditPageModal = (page) => {
  editingPageId.value = page.id
  pageModalForm.value = {
    name: page.name || '',
    industry: page.industry || '',
    target_audience: page.target_audience || '',
    description: page.description || ''
  }
  showPageModal.value = true
}

const closePageModal = () => {
  showPageModal.value = false
  editingPageId.value = null
}

const submitPageModal = async () => {
  if (!pageModalForm.value.name.trim()) return
  const payload = {
    name: pageModalForm.value.name.trim(),
    industry: pageModalForm.value.industry.trim(),
    target_audience: pageModalForm.value.target_audience.trim(),
    description: pageModalForm.value.description.trim()
  }
  const isEditing = !!editingPageId.value
  const targetId = editingPageId.value
  closePageModal()

  if (isEditing) {
    await handleUpdatePage({ id: targetId, data: payload })
  } else {
    const created = await handleCreatePage(payload)
    if (created && created.id) {
      openPageWorkspace(created.id)
    }
  }
}

const confirmDeletePage = (page) => {
  deletingPage.value = page
}

const executeDeletePage = async () => {
  if (!deletingPage.value) return
  const target = deletingPage.value
  deletingPage.value = null
  await handleDeletePage(target)
}

/**
 * Tải dữ liệu ban đầu trực tiếp từ Backend API
 */
const initApp = async () => {
  isConnected.value = true
  fetchLlmHealth(false).then(() => fetchLlmHealth(true))
  await fetchPages()
  await fetchDocuments()
  await fetchInitialRulesCount()
}

const syncSelectedDocsForActivePage = () => {
  const scopedDocs = documents.value.filter(d => {
    if (d.status !== 'READY') return false
    if (!activePageId.value) return true
    if (activePageId.value === '__unassigned__') return !d.page_id
    return d.page_id === activePageId.value
  })
  selectedDocIds.value = scopedDocs.map(d => d.document_id)
}

const handleSelectPage = (pageId) => {
  activePageId.value = pageId
  try {
    if (pageId) {
      localStorage.setItem('docmind_active_page_id', pageId)
    } else {
      localStorage.removeItem('docmind_active_page_id')
    }
  } catch {
    // ignore storage errors
  }
  syncSelectedDocsForActivePage()
  fetchInitialRulesCount()
}

const fetchPages = async () => {
  try {
    pages.value = await api.getPages()
    if (!activePageId.value && pages.value.length > 0) {
      let savedPageId = null
      try {
        savedPageId = localStorage.getItem('docmind_active_page_id')
      } catch {
        // ignore
      }
      if (savedPageId && pages.value.some(p => p.id === savedPageId)) {
        activePageId.value = savedPageId
      } else {
        activePageId.value = pages.value[0].id
      }
    }
  } catch (err) {
    console.error('Fetch pages error:', err)
    showToast(`Lỗi tải danh sách Fanpage: ${err.message}`, 'error')
  }
}

const handleCreatePage = async (payload) => {
  try {
    const created = await api.createPage(payload)
    pages.value.unshift(created)
    handleSelectPage(created.id)
    showToast(`📂 Đã tạo Fanpage "${created.name}" thành công!`, 'success')
    return created
  } catch (err) {
    showToast(`Lỗi tạo Page: ${err.message}`, 'error')
    return null
  }
}

const handleUpdatePage = async ({ id, data }) => {
  try {
    const updated = await api.updatePage(id, data)
    const idx = pages.value.findIndex(p => p.id === id)
    if (idx !== -1 && updated) {
      pages.value[idx] = { ...pages.value[idx], ...updated }
    }
    showToast(`✏️ Đã cập nhật thông tin Page "${data.name}"`, 'success')
  } catch (err) {
    showToast(`Lỗi cập nhật Page: ${err.message}`, 'error')
  }
}

const handleDeletePage = async (page) => {
  try {
    await api.deletePage(page.id)
    pages.value = pages.value.filter(p => p.id !== page.id)
    documents.value.forEach(d => {
      if (d.page_id === page.id) d.page_id = null
    })
    if (activePageId.value === page.id) {
      handleSelectPage(pages.value.length > 0 ? pages.value[0].id : null)
    }
    showToast(`Đã xóa Page "${page.name}". Tài liệu bên trong được chuyển về Chưa phân loại.`, 'info')
  } catch (err) {
    showToast(`Lỗi xóa Page: ${err.message}`, 'error')
  }
}

const handleAssignDocPage = async ({ documentId, docId, pageId }) => {
  const targetDocId = documentId || docId
  try {
    await api.assignDocumentPage(targetDocId, pageId)
    const doc = documents.value.find(d => d.document_id === targetDocId)
    if (doc) {
      doc.page_id = pageId || null
    }
    syncSelectedDocsForActivePage()
    const targetPage = pages.value.find(p => p.id === pageId)
    showToast(
      targetPage
        ? `📂 Đã chuyển tài liệu vào Page "${targetPage.name}"`
        : 'Đã gỡ tài liệu khỏi Page (Chưa phân loại)',
      'success',
      2500
    )
  } catch (err) {
    showToast(`Lỗi gán Page cho tài liệu: ${err.message}`, 'error')
  }
}

const fetchInitialRulesCount = async () => {
  const effectivePageId = currentView.value === 'workspace' && activePage.value
    ? activePage.value.id
    : undefined
  try {
    const list = await api.getRules({ active: true, pageId: effectivePageId })
    activeRulesCount.value = list.length
  } catch (err) {
    console.warn('Fetch initial rules count error:', err)
  }
}

const openTeachAi = (event) => {
  teachingFeedback.value = event
}

const handleTeachSubmitted = ({ messageIndex, learnedRule, regeneratedAnswer, appliedRules, citations }) => {
  if (messageIndex >= 0 && messageIndex < messages.value.length) {
    messages.value[messageIndex].content = regeneratedAnswer
    messages.value[messageIndex].newly_learned_rule = learnedRule
    messages.value[messageIndex].applied_rules = appliedRules || [learnedRule]
    if (citations && citations.length > 0) {
      messages.value[messageIndex].citations = citations
    }
  }
  activeRulesCount.value++
  showToast('✨ AI đã tiếp thu quy tắc mới vào Playbook và tái tạo câu trả lời!', 'success')
  chatAreaRef.value?.scrollToBottom()
}

const handleRulesChanged = ({ rules }) => {
  if (rules) {
    activeRulesCount.value = rules.filter(r => r.active).length
  }
}

const fetchDocuments = async () => {
  try {
    const list = await api.getDocuments()
    documents.value = list
    syncSelectedDocsForActivePage()

    list.filter(d => d.status === 'PARSING').forEach(d => {
      startPolling(d.document_id)
    })
  } catch (err) {
    console.error('Fetch docs error:', err)
    showToast(`Lỗi lấy danh sách tài liệu: ${err.message}`, 'error')
  }
}

const startPolling = (docId) => {
  if (pollingTimers.has(docId)) return

  const timer = setInterval(async () => {
    try {
      const statusRes = await api.getDocumentStatus(docId)
      const idx = documents.value.findIndex(d => d.document_id === docId)
      if (idx !== -1) {
        documents.value[idx] = { ...documents.value[idx], ...statusRes }

        if (statusRes.status === 'READY' || statusRes.status === 'FAILED') {
          clearInterval(timer)
          pollingTimers.delete(docId)

          if (statusRes.status === 'READY') {
            showToast(`Tài liệu "${statusRes.filename}" đã phân tích xong!`, 'success')
            if (!selectedDocIds.value.includes(docId)) {
              selectedDocIds.value.push(docId)
            }
          } else {
            showToast(`Tài liệu "${statusRes.filename}" phân tích thất bại!`, 'error')
          }
        }
      }
    } catch (e) {
      console.warn('Poll error for doc', docId, e)
    }
  }, 2000)

  pollingTimers.set(docId, timer)
}

const handleUpload = async (file) => {
  isUploading.value = true
  const uploadPageId = activePage.value ? activePage.value.id : null

  try {
    const res = await api.uploadDocument(file, uploadPageId)
    showToast(`Đã tải lên "${file.name}". Job ID: ${res.job_id || 'đang xử lý'}`, 'success')

    const existingIndex = documents.value.findIndex(d => d.document_id === res.document_id)
    if (existingIndex !== -1) {
      documents.value[existingIndex].status = res.status || 'PARSING'
      documents.value[existingIndex].page_id = uploadPageId
    } else {
      documents.value.unshift({
        document_id: res.document_id,
        filename: file.name,
        status: res.status || 'PARSING',
        pages: 0,
        page_id: uploadPageId,
        engine_breakdown: {},
        created_at: new Date().toISOString()
      })
    }

    startPolling(res.document_id)
  } catch (err) {
    showToast(`Upload thất bại: ${err.message}`, 'error')
  } finally {
    isUploading.value = false
  }
}

const handleToggleSelect = (docId) => {
  const doc = documents.value.find(d => d.document_id === docId)
  if (doc && doc.status !== 'READY') return

  const idx = selectedDocIds.value.indexOf(docId)
  if (idx !== -1) {
    selectedDocIds.value.splice(idx, 1)
  } else {
    selectedDocIds.value.push(docId)
  }
}

const handleToggleSelectAll = () => {
  const readyIds = documents.value
    .filter(d => {
      if (d.status !== 'READY') return false
      if (!activePageId.value) return true
      if (activePageId.value === '__unassigned__') return !d.page_id
      return d.page_id === activePageId.value
    })
    .map(d => d.document_id)
  const isAll = readyIds.every(id => selectedDocIds.value.includes(id))

  if (isAll) {
    selectedDocIds.value = []
  } else {
    selectedDocIds.value = [...readyIds]
  }
}

const handleAskQuestion = async ({ question, documentIds, topK }) => {
  const prevUserMsg = [...messages.value].reverse().find(m => m.role === 'user')
  const prevAssistantMsg = [...messages.value].reverse().find(m => m.role === 'assistant')
  const currentPageId = activePage.value ? activePage.value.id : null

  messages.value.push({
    role: 'user',
    content: question
  })

  isAsking.value = true
  chatAreaRef.value?.scrollToBottom()

  try {
    const res = await api.chat({
      documentIds,
      question,
      topK,
      previousQuestion: prevUserMsg?.content,
      previousAnswer: prevAssistantMsg?.content,
      pageId: currentPageId
    })

    messages.value.push({
      role: 'assistant',
      question,
      content: res.answer || 'Không tìm thấy câu trả lời phù hợp trong tài liệu.',
      citations: res.citations || [],
      applied_rules: res.applied_rules || [],
      newly_learned_rule: res.newly_learned_rule || null
    })

    if (res.newly_learned_rule) {
      activeRulesCount.value++
      showToast('✨ AI đã tự học quy tắc mới từ phản hồi hội thoại của bạn!', 'success')
    }
  } catch (err) {
    messages.value.push({
      role: 'assistant',
      question,
      content: `⚠️ **Lỗi truy vấn RAG:** ${err.message}\n\nHãy đảm bảo tài liệu đã ở trạng thái READY và service backend đang hoạt động.`,
      citations: [],
      applied_rules: [],
      newly_learned_rule: null
    })
  } finally {
    isAsking.value = false
    chatAreaRef.value?.scrollToBottom()
  }
}

const handleClearChat = () => {
  messages.value = []
}

const confirmDeleteDoc = (doc) => {
  inspectingDoc.value = null
  deletingDoc.value = doc
}

const executeDelete = async () => {
  if (!deletingDoc.value) return
  const doc = deletingDoc.value
  isDeleting.value = true

  try {
    await api.deleteDocument(doc.document_id)

    if (pollingTimers.has(doc.document_id)) {
      clearInterval(pollingTimers.get(doc.document_id))
      pollingTimers.delete(doc.document_id)
    }

    documents.value = documents.value.filter(d => d.document_id !== doc.document_id)
    selectedDocIds.value = selectedDocIds.value.filter(id => id !== doc.document_id)

    showToast(`Đã xóa vĩnh viễn "${doc.filename}" và toàn bộ dữ liệu embedding trong DB!`, 'success')
    deletingDoc.value = null
  } catch (err) {
    showToast(`Lỗi khi xóa tài liệu: ${err.message}`, 'error')
  } finally {
    isDeleting.value = false
  }
}

onMounted(() => {
  initApp()
})

onUnmounted(() => {
  pollingTimers.forEach(timer => clearInterval(timer))
  pollingTimers.clear()
})
</script>

<style scoped>
.app-shell {
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-canvas);
  color: var(--text-main);
}

/* ========================================================================= */
/* TOP HEADER BAR (NotebookLM Style)                                         */
/* ========================================================================= */
.notebook-topbar {
  height: 60px;
  min-height: 60px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: var(--bg-canvas);
  z-index: 20;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}

.btn-back-hub {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 13px;
  border-radius: var(--radius-full);
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  color: var(--accent-primary);
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.16s ease;
  box-shadow: var(--shadow-sm);
  white-space: nowrap;
}

.btn-back-hub:hover {
  background: var(--accent-pill-bg);
  border-color: #A8C7FA;
  color: var(--accent-pill-text);
}

.brand-identity {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-md);
  transition: background 0.15s ease;
}

.brand-identity:hover {
  background: #E9EEF6;
}

.brand-logo-notebook {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--accent-primary);
  color: #FFFFFF;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(11, 87, 208, 0.25);
}

.brand-text-col {
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.brand-name {
  font-size: 1.08rem;
  font-weight: 800;
  color: var(--text-main);
  letter-spacing: -0.02em;
}

.brand-badge-sub {
  font-size: 0.7rem;
  font-weight: 600;
  color: var(--accent-primary);
  background: var(--accent-pill-bg);
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

.topbar-divider {
  color: #C4C7C5;
  font-size: 1.1rem;
  font-weight: 400;
  margin: 0 2px;
}

.workspace-page-switcher {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  padding: 4px 12px;
  border-radius: var(--radius-full);
  box-shadow: var(--shadow-sm);
}

.page-dot-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent-primary);
}

.page-dot-indicator.is-global {
  background: #137333;
}

.topbar-page-select {
  border: none;
  background: transparent;
  color: var(--text-main);
  font-family: var(--font-sans);
  font-size: 0.85rem;
  font-weight: 700;
  outline: none;
  cursor: pointer;
  max-width: 260px;
}

.topbar-industry-pill {
  font-size: 0.74rem;
  font-weight: 600;
  color: #0D652D;
  background: #CEEAD6;
  padding: 3px 10px;
  border-radius: var(--radius-full);
  white-space: nowrap;
}

.topbar-audience-pill {
  font-size: 0.74rem;
  font-weight: 500;
  color: #444746;
  background: #E9EEF6;
  padding: 3px 10px;
  border-radius: var(--radius-full);
  max-width: 190px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.btn-config-page {
  background: transparent;
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-size: 0.75rem;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.btn-config-page:hover {
  background: #FFFFFF;
  color: var(--accent-primary);
  border-color: var(--accent-primary);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.btn-sm {
  padding: 6px 13px;
  font-size: 0.8rem;
}

.topbar-sim-btn {
  font-weight: 700;
}

.sim-live-tag {
  font-size: 0.65rem;
  font-weight: 700;
  background: var(--accent-primary);
  color: #FFFFFF;
  padding: 1px 6px;
  border-radius: var(--radius-full);
}

.topbar-action-btn {
  background: #FFFFFF;
}

.topbar-count-pill {
  background: var(--accent-pill-bg);
  color: var(--accent-pill-text);
  font-size: 0.7rem;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: var(--radius-full);
}

/* ========================================================================= */
/* VIEW 1: NOTEBOOKLM FANPAGE DIRECTORY / HUB                                */
/* ========================================================================= */
.hub-view-container {
  flex: 1;
  overflow-y: auto;
  padding: 12px 28px 48px;
}

.hub-inner {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.hub-hero {
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  padding: 28px 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
  box-shadow: var(--shadow-bento);
}

.hub-hero-text {
  max-width: 700px;
}

.hub-title {
  font-size: 1.75rem;
  font-weight: 800;
  color: var(--text-main);
  letter-spacing: -0.025em;
  margin-bottom: 6px;
}

.hub-subtitle {
  font-size: 0.92rem;
  color: var(--text-secondary);
  line-height: 1.6;
}

.hub-subtitle strong {
  color: var(--accent-primary);
  font-weight: 700;
}

.hub-summary-pills {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.summary-stat-pill {
  background: var(--bg-canvas);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 12px 18px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 130px;
}

.stat-num {
  font-size: 1.4rem;
  font-weight: 800;
  color: var(--accent-primary);
  line-height: 1.1;
}

.stat-label {
  font-size: 0.74rem;
  font-weight: 600;
  color: var(--text-secondary);
  margin-top: 3px;
}

/* Hub Toolbar */
.hub-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.hub-search-box {
  position: relative;
  flex: 1;
  min-width: 280px;
  max-width: 520px;
  display: flex;
  align-items: center;
}

.hub-search-icon {
  position: absolute;
  left: 14px;
  color: var(--text-dim);
  pointer-events: none;
}

.hub-search-input {
  width: 100%;
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-full);
  padding: 10px 36px 10px 42px;
  font-family: var(--font-sans);
  font-size: 0.88rem;
  color: var(--text-main);
  outline: none;
  box-shadow: var(--shadow-sm);
  transition: all 0.18s ease;
}

.hub-search-input:focus {
  border-color: var(--accent-primary);
  box-shadow: var(--shadow-glow);
}

.hub-search-clear {
  position: absolute;
  right: 12px;
  border: none;
  background: none;
  color: var(--text-dim);
  font-size: 1.15rem;
  cursor: pointer;
}

.hub-filter-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.hub-filter-pill {
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-family: var(--font-sans);
  font-size: 0.8rem;
  font-weight: 600;
  padding: 7px 14px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: all 0.15s ease;
}

.hub-filter-pill:hover {
  background: #E9EEF6;
  color: var(--text-main);
}

.hub-filter-pill.active {
  background: var(--accent-pill-bg);
  border-color: #A8C7FA;
  color: var(--accent-pill-text);
}

/* NotebookLM Cards Grid */
.notebooks-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 18px;
}

.notebook-card {
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  padding: 20px 22px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 245px;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.2, 0, 0, 1);
  box-shadow: var(--shadow-bento);
  position: relative;
  overflow: hidden;
}

.notebook-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-md);
  border-color: #A8C7FA;
}

/* Create New Fanpage Card */
.create-notebook-card {
  border: 2px dashed #A8C7FA;
  background: rgba(255, 255, 255, 0.72);
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 10px;
}

.create-notebook-card:hover {
  background: #FFFFFF;
  border-color: var(--accent-primary);
}

.create-card-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--accent-pill-bg);
  color: var(--accent-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4px;
}

.create-card-title {
  font-size: 1.05rem;
  font-weight: 700;
  color: var(--accent-primary);
}

.create-card-desc {
  font-size: 0.82rem;
  color: var(--text-secondary);
  line-height: 1.5;
  max-width: 240px;
}

/* NotebookLM Pastel Themes for Page Cards */
.page-notebook-card::before {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 6px;
  background: var(--pastel-blue-bg);
}

.nb-theme-blue::before { background: #A8C7FA; }
.nb-theme-mint::before { background: #81C995; }
.nb-theme-amber::before { background: #FDD663; }
.nb-theme-lavender::before { background: #C58AF9; }

.nb-theme-blue .notebook-icon-wrap {
  background: var(--pastel-blue-bg);
  color: var(--pastel-blue-text);
}
.nb-theme-mint .notebook-icon-wrap {
  background: var(--pastel-mint-bg);
  color: var(--pastel-mint-text);
}
.nb-theme-amber .notebook-icon-wrap {
  background: var(--pastel-amber-bg);
  color: var(--pastel-amber-text);
}
.nb-theme-lavender .notebook-icon-wrap {
  background: var(--pastel-lavender-bg);
  color: var(--pastel-lavender-text);
}

.notebook-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
}

.notebook-icon-wrap {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.25rem;
  flex-shrink: 0;
}

.global-icon-wrap {
  background: #E8F0FE;
  color: var(--accent-primary);
}

.notebook-top-badges {
  flex: 1;
  min-width: 0;
}

.notebook-industry-tag {
  display: inline-block;
  font-size: 0.72rem;
  font-weight: 700;
  color: var(--text-secondary);
  background: var(--bg-canvas);
  border: 1px solid var(--border-subtle);
  padding: 3px 10px;
  border-radius: var(--radius-full);
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.global-tag {
  background: var(--accent-pill-bg);
  color: var(--accent-pill-text);
  border-color: #A8C7FA;
}

.notebook-card-menu {
  display: flex;
  align-items: center;
  gap: 4px;
}

.card-icon-btn {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--text-dim);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.card-icon-btn:hover {
  background: var(--bg-canvas);
  color: var(--text-main);
}

.card-icon-del:hover {
  background: #FCE8E6;
  color: #C5221F;
}

.notebook-card-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.notebook-title {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--text-main);
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notebook-audience-row {
  display: flex;
  align-items: baseline;
  gap: 5px;
  font-size: 0.78rem;
}

.audience-label {
  color: var(--text-dim);
  font-weight: 600;
  white-space: nowrap;
}

.audience-val {
  color: var(--text-secondary);
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notebook-persona-desc {
  font-size: 0.81rem;
  color: var(--text-dim);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notebook-card-footer {
  padding-top: 12px;
  border-top: 1px solid #EEF2F6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.notebook-stats-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.nb-stat-chip {
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--text-secondary);
  background: var(--bg-canvas);
  padding: 3px 9px;
  border-radius: var(--radius-full);
}

.nb-stat-chip.has-docs {
  background: #E6F4EA;
  color: #137333;
}

.notebook-cta-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-card-sim {
  background: var(--accent-pill-bg);
  color: var(--accent-pill-text);
  border: none;
  border-radius: var(--radius-full);
  padding: 5px 10px;
  font-size: 0.73rem;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-card-sim:hover {
  background: var(--accent-primary);
  color: #FFFFFF;
}

.btn-card-open {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 0.78rem;
  font-weight: 700;
  color: var(--accent-primary);
}

/* ========================================================================= */
/* VIEW 2: WORKSPACE FLOATING BENTO LAYOUT                                   */
/* ========================================================================= */
.main-layout {
  flex: 1;
  display: flex;
  gap: 16px;
  padding: 0 16px 16px;
  min-height: 0;
  overflow: hidden;
  background: var(--bg-canvas);
}

/* ========================================================================= */
/* MODALS IN APP.VUE (NotebookLM Clean Daylight Surface)                     */
/* ========================================================================= */
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.modal-card {
  width: 100%;
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
  animation: modalEnter 0.2s ease-out;
}

.page-persona-modal-card {
  max-width: 560px;
}

.modal-delete-card {
  max-width: 480px;
}

@keyframes modalEnter {
  from {
    opacity: 0;
    transform: scale(0.96) translateY(8px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

.modal-header {
  padding: 18px 24px;
  border-bottom: 1px solid var(--border-subtle);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #F8FAFD;
}

.modal-title-group {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-modal-icon {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--accent-pill-bg);
  color: var(--accent-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.2rem;
}

.title-icon-danger {
  color: #C5221F;
}

.modal-title {
  font-size: 1.05rem;
  font-weight: 700;
  color: var(--text-main);
}

.modal-subtitle-text {
  font-size: 0.78rem;
  color: var(--text-secondary);
  margin-top: 2px;
}

.text-danger .modal-title {
  color: #C5221F;
}

.btn-close {
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 1.5rem;
  cursor: pointer;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 50%;
}

.btn-close:hover {
  color: var(--text-main);
  background: #E9EEF6;
}

.modal-body {
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-field-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-row-2col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.field-label {
  font-size: 0.8rem;
  font-weight: 700;
  color: var(--text-main);
}

.req-star {
  color: #C5221F;
}

.modal-input {
  width: 100%;
  background: #F8FAFD;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 9px 12px;
  font-family: var(--font-sans);
  font-size: 0.86rem;
  color: var(--text-main);
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.modal-input:focus {
  background: #FFFFFF;
  border-color: var(--accent-primary);
  box-shadow: var(--shadow-glow);
}

.modal-textarea {
  resize: vertical;
  min-height: 76px;
  line-height: 1.45;
}

.field-helper {
  font-size: 0.74rem;
  color: var(--text-dim);
}

.delete-warning-text {
  font-size: 0.92rem;
  color: var(--text-main);
  line-height: 1.5;
}

.danger-box {
  background: #FCE8E6;
  border: 1px solid #FAD2CF;
  border-radius: var(--radius-md);
  padding: 12px 14px;
}

.danger-box-title {
  font-size: 0.76rem;
  font-weight: 700;
  color: #C5221F;
  margin-bottom: 6px;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.danger-box-list {
  padding-left: 18px;
  font-size: 0.8rem;
  color: #8C1D18;
  line-height: 1.55;
}

.modal-footer {
  padding: 14px 24px;
  border-top: 1px solid var(--border-subtle);
  background: #F8FAFD;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

/* Toast Banner */
.toast-banner {
  position: fixed;
  top: 16px;
  right: 20px;
  z-index: 1500;
  padding: 10px 18px;
  border-radius: var(--radius-full);
  font-size: 0.82rem;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 12px;
  box-shadow: var(--shadow-lg);
}

.toast-banner.success {
  background: #137333;
  color: #ffffff;
}

.toast-banner.info {
  background: #0B57D0;
  color: #ffffff;
}

.toast-banner.error {
  background: #C5221F;
  color: #ffffff;
}

.toast-close {
  background: none;
  border: none;
  color: inherit;
  font-size: 1.1rem;
  cursor: pointer;
  padding: 0 2px;
  line-height: 1;
}

.toast-enter-active,
.toast-leave-active {
  transition: all 0.25s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-12px);
}

/* LLM & API Key Healthcheck Pill */
.llm-health-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 11px;
  border-radius: 999px;
  font-family: var(--font-sans);
  font-size: 0.76rem;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid #DDE3EA;
  background: #FFFFFF;
  color: #1F1F1F;
  transition: all 0.18s ease;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.llm-health-pill:hover {
  transform: translateY(-1px);
  box-shadow: 0 3px 8px rgba(11, 87, 208, 0.12);
}

.llm-health-pill.status-healthy {
  background: #E6F4EA;
  border-color: #A8DAB5;
  color: #0D652D;
}

.llm-health-pill.status-configured,
.llm-health-pill.status-checking {
  background: #FEF7E0;
  border-color: #FDD663;
  color: #B06000;
}

.llm-health-pill.status-error {
  background: #FCE8E6;
  border-color: #F6AEA9;
  color: #C5221F;
}

.llm-health-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.llm-health-dot.is-pulsing {
  animation: llmPulse 1s infinite ease-in-out;
}

@keyframes llmPulse {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.35); opacity: 0.45; }
}

.llm-health-label strong {
  font-weight: 700;
}

.llm-health-badge {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 0.7rem;
  font-family: 'JetBrains Mono', monospace;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(0, 0, 0, 0.08);
}

/* LLM Healthcheck Modal */
.modal-llm-health-card {
  max-width: 620px;
  width: 94%;
}

.llm-health-modal-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.llm-status-banner {
  padding: 13px 16px;
  border-radius: 14px;
  border: 1px solid #DDE3EA;
  background: #F8FAFD;
}

.llm-status-banner.status-healthy {
  background: #E6F4EA;
  border-color: #A8DAB5;
  color: #0D652D;
}

.llm-status-banner.status-configured,
.llm-status-banner.status-checking {
  background: #FEF7E0;
  border-color: #FDD663;
  color: #8F4B00;
}

.llm-status-banner.status-error {
  background: #FCE8E6;
  border-color: #F6AEA9;
  color: #A50E0E;
}

.llm-status-banner-top {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.llm-status-icon {
  font-size: 1.35rem;
  line-height: 1.2;
}

.llm-status-text-wrap {
  flex: 1;
}

.llm-status-code {
  font-size: 0.86rem;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 4px;
}

.llm-http-chip,
.llm-latency-chip {
  font-size: 0.72rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid rgba(0, 0, 0, 0.1);
}

.llm-status-desc {
  font-size: 0.82rem;
  line-height: 1.45;
  opacity: 0.95;
}

.llm-info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.llm-info-item {
  background: #F8FAFD;
  border: 1px solid #E1E6EF;
  border-radius: 12px;
  padding: 10px 13px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.llm-info-item.llm-info-full {
  grid-column: 1 / -1;
}

.llm-info-label {
  font-size: 0.72rem;
  font-weight: 700;
  color: #5F6368;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.llm-info-value {
  font-size: 0.85rem;
  font-weight: 700;
  color: #1F1F1F;
  display: flex;
  align-items: center;
  gap: 8px;
  word-break: break-all;
}

.llm-info-value.code-font {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 0.8rem;
}

.key-status-tag {
  font-family: var(--font-sans);
  font-size: 0.68rem;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
}

.key-status-tag.tag-ok {
  background: #CEEAD6;
  color: #0D652D;
}

.key-status-tag.tag-missing {
  background: #FCE8E6;
  color: #C5221F;
}

.llm-ping-output-box {
  background: #F1F5F9;
  border: 1px solid #CBD5E1;
  border-radius: 12px;
  padding: 10px 13px;
}

.llm-ping-output-title {
  font-size: 0.75rem;
  font-weight: 700;
  color: #334155;
  margin-bottom: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.llm-checked-time {
  font-weight: 500;
  color: #64748B;
}

.llm-ping-pre {
  margin: 0;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 0.78rem;
  color: #0F172A;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 110px;
  overflow-y: auto;
}

.llm-ping-pre.is-error {
  color: #B91C1C;
}

.llm-env-hint {
  font-size: 0.76rem;
  color: #444746;
  background: #E8F0FE;
  border: 1px solid #D3E3FD;
  border-radius: 10px;
  padding: 9px 12px;
  line-height: 1.45;
}

/* ========================================================================= */
/* FLOATING BUBBLES DOCK & INTERNAL Q&A DRAWER WINDOW                        */
/* ========================================================================= */
.floating-bubbles-dock {
  position: fixed;
  right: 22px;
  bottom: 18px;
  z-index: 950;
  display: flex;
  align-items: center;
  gap: 10px;
}

.floating-bubble-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 800;
  cursor: pointer;
  border: 1.5px solid transparent;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.16);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.floating-bubble-pill:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.22);
}

.bubble-qa {
  background: #ffffff;
  color: #1e293b;
  border-color: #cbd5e1;
}

.bubble-qa.active {
  background: #1e293b;
  color: #ffffff;
  border-color: #0f172a;
}

.bubble-test-bot {
  background: linear-gradient(135deg, #0b57d0, #1a73e8);
  color: #ffffff;
  border-color: rgba(255, 255, 255, 0.25);
}

.bubble-icon {
  font-size: 16px;
}

.bubble-count-tag {
  background: #e2e8f0;
  color: #0f172a;
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
}

.bubble-live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #4ade80;
  box-shadow: 0 0 0 3px rgba(74, 222, 128, 0.3);
}

.floating-qa-drawer-window {
  position: fixed;
  right: 22px;
  bottom: 70px;
  width: min(640px, calc(100vw - 44px));
  height: min(680px, calc(100vh - 140px));
  z-index: 940;
  background: #ffffff;
  border: 1.5px solid #cbd5e1;
  border-radius: 22px;
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.22);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.floating-qa-topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  background: #0f172a;
  color: #ffffff;
}

.floating-qa-title {
  display: flex;
  flex-direction: column;
  font-size: 13px;
  font-weight: 800;
}

.floating-qa-sub {
  font-size: 11px;
  font-weight: 500;
  color: #94a3b8;
}

.btn-minimize-qa {
  padding: 5px 11px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.25);
  background: rgba(255, 255, 255, 0.1);
  color: #ffffff;
  font-size: 11.5px;
  font-weight: 700;
  cursor: pointer;
}

.btn-minimize-qa:hover {
  background: rgba(255, 255, 255, 0.2);
}

.floating-qa-body {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.floating-qa-body :deep(.chat-container) {
  border: none !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  height: 100% !important;
}

/* ========================================================================= */
/* MOBILE & TABLET RESPONSIVENESS (<= 960px & <= 640px)                      */
/* ========================================================================= */
@media (max-width: 960px) {
  .notebook-topbar {
    height: auto;
    min-height: 52px;
    padding: 8px 12px;
    flex-wrap: wrap;
    gap: 8px;
  }

  .topbar-left {
    width: 100%;
    flex-wrap: wrap;
    gap: 6px;
  }

  .topbar-right {
    width: 100%;
    overflow-x: auto;
    padding-bottom: 2px;
    gap: 6px;
    justify-content: flex-start;
  }

  .brand-badge-sub,
  .topbar-divider,
  .topbar-industry-pill,
  .topbar-audience-pill {
    display: none;
  }

  .topbar-page-select {
    max-width: 185px;
    font-size: 0.8rem;
  }

  .main-layout {
    padding: 0 8px 54px;
    gap: 8px;
  }

  .floating-bubbles-dock {
    right: 10px;
    left: 10px;
    bottom: 8px;
    justify-content: flex-end;
    gap: 8px;
  }

  .floating-bubble-pill {
    padding: 8px 13px;
    font-size: 12px;
    box-shadow: 0 4px 14px rgba(15, 23, 42, 0.16);
  }

  .floating-qa-drawer-window {
    right: 8px;
    left: 8px;
    bottom: 52px;
    width: auto;
    height: calc(100dvh - 116px);
    border-radius: 18px;
  }
}

@media (max-width: 640px) {
  .btn-back-hub span {
    display: none;
  }

  .btn-back-hub {
    padding: 6px 9px;
  }

  .brand-logo-notebook {
    width: 28px;
    height: 28px;
  }

  .brand-name {
    font-size: 0.96rem;
  }

  .btn-config-page {
    padding: 4px 8px;
    font-size: 0.72rem;
  }

  .btn-sm {
    padding: 5px 10px;
    font-size: 0.74rem;
    white-space: nowrap;
  }

  .llm-health-badge {
    display: none;
  }

  .hub-view-container {
    padding: 10px 12px 72px;
  }

  .hub-hero {
    padding: 18px 16px;
    gap: 14px;
    border-radius: 18px;
  }

  .hub-title {
    font-size: clamp(1.25rem, 1rem + 2vw, 1.5rem);
  }

  .hub-subtitle {
    font-size: 0.84rem;
  }

  .hub-summary-pills {
    width: 100%;
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;
  }

  .summary-stat-pill {
    min-width: 0;
    padding: 10px;
  }

  .stat-num {
    font-size: 1.15rem;
  }

  .stat-label {
    font-size: 0.68rem;
  }

  .hub-search-box {
    min-width: 100%;
  }

  .notebooks-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .notebook-card {
    padding: 16px;
    min-height: 200px;
  }

  .modal-backdrop {
    padding: 10px;
  }

  .modal-header {
    padding: 14px 16px;
  }

  .modal-body {
    padding: 14px 16px;
  }

  .modal-footer {
    padding: 12px 16px;
    flex-wrap: wrap;
  }

  .form-row-2col,
  .llm-info-grid {
    grid-template-columns: 1fr;
  }
}
</style>
