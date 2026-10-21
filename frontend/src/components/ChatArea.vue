<template>
  <main class="chat-container">
    <!-- Chat Header -->
    <header class="chat-header">
      <div class="header-left">
        <div class="context-indicator">
          <div class="pulse-sparkle">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/>
            </svg>
          </div>
          <div>
            <div class="header-title-row-chat">
              <h3 class="header-title">Hỏi đáp tài liệu (RAG Grounded)</h3>
              <span v-if="activePage" class="active-page-pill">
                📂 {{ activePage.name }}
                <template v-if="activePage.industry"> · 🏷️ {{ activePage.industry }}</template>
                <template v-if="activePage.target_audience"> · 👥 {{ activePage.target_audience }}</template>
                <template v-if="activePage.description"> · 🎭 Có Persona</template>
              </span>
              <span v-else class="active-page-pill is-global">
                🌐 Tất cả các Page
              </span>
            </div>
            <p class="context-subtitle">
              <span v-if="selectedDocs.length > 0">
                Phạm vi: <strong>{{ selectedDocs.length }} tài liệu</strong> ({{ selectedDocsNames }})
              </span>
              <span v-else class="text-warning">
                ⚠️ Chưa chọn tài liệu nào. Vui lòng tick chọn ít nhất 1 tài liệu ở bên trái!
              </span>
            </p>
          </div>
        </div>
      </div>

      <div class="header-right">
        <!-- Q&A Test Suite Toggle Button -->
        <button 
          class="btn btn-secondary btn-sm qa-test-header-btn" 
          :class="{ 'is-active': showQaTester }"
          title="Bật/Tắt Bộ Kịch bản Kiểm thử Q&A theo Page & Ngành nghề"
          @click="showQaTester = !showQaTester"
        >
          <span>🧪 Kiểm thử Q&A</span>
          <span class="badge-qa-count">{{ qaTestSuite.scenarios.length }}</span>
        </button>

        <!-- AI Playbook Button -->
        <button 
          class="btn btn-secondary btn-sm playbook-header-btn" 
          title="Mở Bộ nhớ Kinh nghiệm AI (AI Playbook)"
          @click="$emit('open-playbook')"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z"/>
            <path d="M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z"/>
            <path d="M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4"/>
          </svg>
          <span>Bộ nhớ AI (Playbook)</span>
          <span v-if="activeRulesCount > 0" class="badge-mini-count">{{ activeRulesCount }}</span>
        </button>

        <!-- Agent Skill Registry Button -->
        <button
          class="btn btn-secondary btn-sm playbook-header-btn"
          title="Mở Kho Kỹ năng Chuyên gia AI (Tâm lý học, Chốt Sale & Giao tiếp)"
          @click="$emit('open-skills')"
        >
          <span>🎯 Kỹ năng Agent</span>
        </button>

        <!-- Top-K Setting -->
        <div class="top-k-control" title="Số lượng chunk ngữ cảnh tối đa gửi vào prompt RAG">
          <label for="topk-select">Top-K:</label>
          <select id="topk-select" v-model="topK">
            <option :value="3">3 chunks</option>
            <option :value="5">5 chunks (chuẩn)</option>
            <option :value="10">10 chunks (sâu)</option>
            <option :value="20">20 chunks</option>
          </select>
        </div>

        <!-- Clear Chat Button -->
        <button 
          v-if="messages.length > 0" 
          class="btn btn-secondary btn-sm" 
          title="Xóa toàn bộ cuộc hội thoại"
          @click="clearChat"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 6h18"/>
            <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/>
            <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/>
          </svg>
          Xóa chat
        </button>
      </div>
    </header>

    <!-- Collapsible Q&A Testing & Page Persona Bar (Available Anytime) -->
    <div v-if="showQaTester" class="qa-tester-drawer">
      <div class="qa-drawer-header">
        <div class="qa-drawer-title-group">
          <span class="qa-badge-pill">🧪 BỘ KIỂM THỬ Q&A THEO PAGE & NGÀNH NGHỀ</span>
          <span class="qa-persona-summary">
            <strong>{{ qaTestSuite.profileTitle }}</strong> · Giọng văn: <em>{{ qaTestSuite.personaTone }}</em>
          </span>
        </div>
        <button class="btn-close-qa" @click="showQaTester = false" title="Thu gọn bảng kiểm thử">×</button>
      </div>
      <div class="qa-scenarios-strip">
        <button
          v-for="(sc, sIdx) in qaTestSuite.scenarios"
          :key="sIdx"
          class="qa-scenario-chip"
          :disabled="selectedDocs.length === 0 || isAsking"
          @click="submitQuestion(sc.question)"
          :title="'Tiêu chí chấm: ' + sc.criteria"
        >
          <div class="qa-chip-top">
            <span class="qa-chip-tag">{{ sc.tag }}</span>
            <span class="qa-chip-run">Chạy thử →</span>
          </div>
          <div class="qa-chip-q">{{ sc.question }}</div>
          <div class="qa-chip-crit">🎯 {{ sc.criteria }}</div>
        </button>
      </div>
    </div>

    <!-- Messages Scroll Area -->
    <div ref="messagesScrollRef" class="messages-area">
      <!-- Empty State -->
      <div v-if="messages.length === 0" class="empty-chat-state">
        <div class="hero-icon-bubble">
          <svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          </svg>
        </div>
        <h2 class="hero-title">
          {{ activePage ? `Trợ lý Sale: ${activePage.name}` : 'Chào mừng đến với DocMind Sale-Ready AI' }}
        </h2>
        <p class="hero-desc">
          Hệ thống trả lời <strong>chính xác 100%</strong> theo tài liệu với cấu trúc <strong>2 tầng Sale-Ready</strong>: Tra cứu nội bộ kèm trích dẫn <code>[1]</code> & Tin nhắn tư vấn khéo léo theo đúng <strong>Page Persona</strong> ({{ qaTestSuite.personaTone }}).
        </p>

        <!-- Dynamic Page & Industry Starter Scenarios -->
        <div class="starters-container">
          <div class="starters-label">
            🧪 Kịch bản kiểm thử Q&A chuẩn cho {{ qaTestSuite.profileTitle }}:
          </div>
          <div class="starters-grid">
            <button 
              v-for="(sc, idx) in qaTestSuite.scenarios" 
              :key="idx" 
              class="starter-card"
              :disabled="selectedDocs.length === 0"
              @click="submitQuestion(sc.question)"
            >
              <div class="starter-icon">{{ sc.icon }}</div>
              <div class="starter-body">
                <div class="starter-tag-row">
                  <span class="starter-tag">{{ sc.tag }}</span>
                </div>
                <div class="starter-text">{{ sc.question }}</div>
                <div class="starter-criteria">Tiêu chí: {{ sc.criteria }}</div>
              </div>
            </button>
          </div>
        </div>
      </div>

      <!-- Messages Stream -->
      <div v-else class="messages-list">
        <div 
          v-for="(msg, index) in messages" 
          :key="index"
          class="message-row"
          :class="msg.role === 'user' ? 'row-user' : 'row-assistant'"
        >
          <!-- Assistant Avatar -->
          <div v-if="msg.role === 'assistant'" class="avatar-assistant">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/>
            </svg>
          </div>

          <!-- Message Body -->
          <div class="message-bubble" :class="msg.role">
            <!-- User text -->
            <div v-if="msg.role === 'user'" class="user-text">
              {{ msg.content }}
            </div>

            <!-- Assistant Markdown & Citations -->
            <div v-else class="assistant-content">
              <!-- Applied Rules Expandable Badge Section -->
              <div v-if="msg.applied_rules && msg.applied_rules.length > 0" class="applied-rules-container">
                <button 
                  class="applied-rules-toggle" 
                  :class="{ 'is-expanded': expandedRulesIndices.includes(index) }"
                  @click="toggleRulesExpand(index)"
                  title="Nhấn để xem các quy tắc kinh nghiệm đã áp dụng"
                >
                  <span class="lightning-icon">⚡</span>
                  <span class="rules-summary-text">
                    Đã áp dụng <strong>{{ msg.applied_rules.length }} quy tắc</strong> kinh nghiệm
                  </span>
                  <svg class="chevron-icon" :class="{ 'rotate-180': expandedRulesIndices.includes(index) }" xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="6 9 12 15 18 9"/>
                  </svg>
                </button>

                <div v-if="expandedRulesIndices.includes(index)" class="applied-rules-details">
                  <div 
                    v-for="(rule, rIdx) in msg.applied_rules" 
                    :key="rIdx" 
                    class="applied-rule-item"
                  >
                    <div class="applied-rule-top">
                      <span class="category-tag-mini">{{ rule.category || 'RULE' }}</span>
                      <button class="link-open-playbook" @click="$emit('open-playbook')">
                        Xem Playbook →
                      </button>
                    </div>
                    <p class="applied-rule-content">{{ rule.generalized_rule }}</p>
                  </div>
                </div>
              </div>

              <!-- Newly Learned Rule Highlight Callout Card -->
              <div v-if="msg.newly_learned_rule" class="newly-learned-callout">
                <div class="callout-header">
                  <div class="callout-title-row">
                    <span class="sparkle-emoji">✨</span>
                    <strong class="callout-title">AI đã đúc kết kinh nghiệm mới (Meta-Rule)</strong>
                  </div>
                  <span class="category-tag-mini">{{ msg.newly_learned_rule.category || 'LEARNED' }}</span>
                </div>
                
                <div v-if="msg.newly_learned_rule.root_cause" class="callout-line">
                  <span class="line-label">Nguyên nhân lỗi:</span>
                  <span class="line-val">{{ msg.newly_learned_rule.root_cause }}</span>
                </div>

                <div class="callout-line highlight-rule">
                  <span class="line-label">Quy tắc tổng quát:</span>
                  <p class="rule-text-val">{{ msg.newly_learned_rule.generalized_rule }}</p>
                </div>

                <div class="callout-footer">
                  <button class="btn-playbook-link" @click="$emit('open-playbook')">
                    Xem trong Bộ nhớ AI (Playbook) →
                  </button>
                </div>
              </div>

              <!-- Sale-Ready & Grounded Quality Scorecard Bar -->
              <div class="quality-scorecard-bar">
                <span 
                  v-for="(badge, bIdx) in evaluateResponseQuality(msg)" 
                  :key="bIdx"
                  class="q-score-badge"
                  :class="badge.passed ? 'is-pass' : 'is-neutral'"
                  :title="badge.tooltip"
                >
                  {{ badge.icon }} {{ badge.label }}
                </span>
              </div>

              <!-- Answer Body -->
              <div class="markdown-body" v-html="renderMarkdown(msg.content)"></div>

              <!-- Citations Section -->
              <div v-if="msg.citations && msg.citations.length > 0" class="citations-section">
                <div class="citations-header-row">
                  <span class="citations-label">
                    <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M12 2H2v10l9.29 9.29c.94.94 2.48.94 3.42 0l6.58-6.58c.94-.94.94-2.48 0-3.42L12 2Z"/>
                      <path d="M7 7h.01"/>
                    </svg>
                    Nguồn trích dẫn kiểm chứng ({{ msg.citations.length }})
                  </span>
                </div>

                <div class="citations-grid">
                  <CitationCard 
                    v-for="(cite, cIdx) in msg.citations"
                    :key="cIdx"
                    :citation="cite"
                    :doc-name="getDocName(cite.document_id)"
                  />
                </div>
              </div>

              <!-- Action Bar -->
              <div class="message-actions">
                <!-- Copy Sale-Ready Customer Message Button -->
                <button 
                  v-if="hasSaleReadySection(msg.content)"
                  class="action-btn action-btn-sale-copy" 
                  :title="copiedSaleIndex === index ? 'Đã copy tin nhắn mẫu gửi khách' : 'Chỉ copy phần Tin nhắn mẫu gửi khách (đã lọc bỏ ký hiệu trích dẫn [1])'"
                  @click="copySaleReadyMessage(msg.content, index)"
                >
                  <span>{{ copiedSaleIndex === index ? '✅ Đã copy tin gửi khách!' : '💬 Copy tin nhắn gửi khách (Sale-Ready)' }}</span>
                </button>

                <button 
                  class="action-btn" 
                  :title="copiedIndex === index ? 'Đã sao chép' : 'Sao chép toàn bộ câu trả lời'"
                  @click="copyAnswer(msg.content, index)"
                >
                  <svg v-if="copiedIndex !== index" xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <rect width="14" height="14" x="8" y="8" rx="2" ry="2"/>
                    <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>
                  </svg>
                  <svg v-else xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#34d399" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="20 6 9 17 4 12"/>
                  </svg>
                  <span>{{ copiedIndex === index ? 'Đã sao chép' : 'Sao chép tất cả' }}</span>
                </button>

                <!-- Báo sai & Dạy AI Action Button -->
                <button 
                  class="action-btn action-btn-teach" 
                  title="Báo câu trả lời chưa chính xác và hướng dẫn AI quy tắc đúng"
                  @click="openTeachModal(msg, index)"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M12 20h9"/>
                    <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/>
                  </svg>
                  <span>Báo sai & Dạy AI</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- Loading Indicator -->
        <div v-if="isAsking" class="message-row row-assistant">
          <div class="avatar-assistant pulse-glow">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/>
            </svg>
          </div>
          <div class="message-bubble assistant loading-bubble">
            <div class="retrieval-indicator">
              <div class="typing-dots">
                <span></span>
                <span></span>
                <span></span>
              </div>
              <span class="loading-label">Đang thực hiện Dense Embedding + BM25 Hybrid Retrieval & định hình Page Persona...</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Chat Input Section -->
    <footer class="chat-input-wrapper">
      <div class="input-card" :class="{ 'has-focus': isFocused, 'is-disabled': selectedDocs.length === 0 }">
        <textarea 
          ref="inputRef"
          v-model="questionInput" 
          placeholder="Đặt câu hỏi khách hàng hoặc bấm '🧪 Kiểm thử Q&A' ở góc trên để chạy bộ kịch bản chuẩn..."
          rows="1"
          :disabled="selectedDocs.length === 0 || isAsking"
          @focus="isFocused = true"
          @blur="isFocused = false"
          @keydown.enter="handleEnter"
          @input="autoResize"
        ></textarea>

        <div class="input-bottom-row">
          <div class="hint-text">
            <span><strong>Enter</strong> để gửi · <strong>Shift+Enter</strong> xuống dòng</span>
          </div>

          <button 
            class="btn btn-send" 
            :disabled="!canSend"
            @click="submitQuestion(questionInput)"
          >
            <span v-if="!isAsking">Gửi</span>
            <div v-else class="btn-spinner"></div>
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="22" y1="2" x2="11" y2="13"/>
              <polygon points="22 2 15 22 11 13 2 9 22 2"/>
            </svg>
          </button>
        </div>
      </div>
    </footer>

    <!-- Fanpage FB Messenger Bot Simulator Modal (Teleported to body so it works even when Q&A is minimized to a floating bubble) -->
    <Teleport to="body">
      <FanpageBotSimulatorModal
        :is-open="showFanpageSimulator"
        :active-page="activePage"
        :pages="pages"
        :selected-docs="selectedDocs"
        :is-connected="isConnected"
        @close="showFanpageSimulator = false; $emit('simulator-closed')"
      />
    </Teleport>
  </main>
</template>

<script setup>
import { ref, computed, nextTick } from 'vue'
import { marked } from 'marked'
import CitationCard from './CitationCard.vue'
import FanpageBotSimulatorModal from './FanpageBotSimulatorModal.vue'

const props = defineProps({
  selectedDocs: {
    type: Array,
    default: () => []
  },
  allDocs: {
    type: Array,
    default: () => []
  },
  pages: {
    type: Array,
    default: () => []
  },
  messages: {
    type: Array,
    default: () => []
  },
  isAsking: {
    type: Boolean,
    default: false
  },
  activeRulesCount: {
    type: Number,
    default: 0
  },
  activePage: {
    type: Object,
    default: null
  },
  isConnected: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['ask-question', 'clear-chat', 'teach-ai', 'open-playbook', 'open-skills', 'simulator-closed'])

const topK = ref(5)
const questionInput = ref('')
const isFocused = ref(false)
const inputRef = ref(null)
const messagesScrollRef = ref(null)
const copiedIndex = ref(-1)
const copiedSaleIndex = ref(-1)
const expandedRulesIndices = ref([])
const showQaTester = ref(false)
const showFanpageSimulator = ref(false)

const toggleRulesExpand = (index) => {
  const i = expandedRulesIndices.value.indexOf(index)
  if (i !== -1) {
    expandedRulesIndices.value.splice(i, 1)
  } else {
    expandedRulesIndices.value.push(index)
  }
}

const openTeachModal = (msg, index) => {
  let q = msg.question || ''
  if (!q && index > 0 && props.messages[index - 1]?.role === 'user') {
    q = props.messages[index - 1].content
  }
  const appliedRuleIds = (msg.applied_rules || [])
    .map(r => r?.id)
    .filter(Boolean)

  emit('teach-ai', {
    message: msg,
    messageIndex: index,
    question: q,
    faultyAnswer: msg.content,
    documentIds: props.selectedDocs.map(d => d.document_id),
    appliedRuleIds
  })
}

/**
 * Bộ Kịch bản Kiểm thử Q&A Động theo Page & Ngành nghề (Spa, B2B/SaaS, BĐS, hoặc Đa ngành)
 */
const qaTestSuite = computed(() => {
  const p = props.activePage
  const ind = (p?.industry || '').toLowerCase()
  const name = (p?.name || '').toLowerCase()
  const customDesc = p?.description || ''

  if (ind.includes('spa') || ind.includes('làm đẹp') || ind.includes('thẩm mỹ') || name.includes('sen') || name.includes('aura')) {
    return {
      profileTitle: `${p?.name || 'Page Spa'} (${p?.industry || 'Làm đẹp'} · KH ${p?.target_audience || 'Nữ 25-50'})`,
      personaTone: customDesc || 'Xưng Em - Chị dịu dàng, nêu quà tặng & cam kết Y khoa trước khi báo giá, gợi mở đặt lịch soi da',
      scenarios: [
        {
          icon: '💰',
          tag: '1. Báo giá & Quà tặng',
          question: 'Chị muốn hỏi giá Phác đồ Trị Nám Laser Pico (5 buổi) và gói Căng bóng Trẻ hóa da Meso Collagen (1 buổi), bên em có tặng kèm hay bảo hành gì không em?',
          criteria: 'Báo đúng SPA-03 (4.500.000đ/buổi, BH 12 tháng), SPA-02 (3.200.000đ, tặng mặt nạ B5) & gợi ý Combo Toàn Diện 5 buổi (giảm 15% + tặng bộ kit)'
        },
        {
          icon: '📊',
          tag: '2. Chiết khấu Combo & Loại trừ',
          question: 'Nếu chị đăng ký Combo 5 buổi Trị Nám Laser Pico và chị đang có Voucher Sinh Nhật hoặc Flash Sale giờ vàng thì có được cộng dồn giảm giá không? Tổng chi tiêu trên 20 triệu có thêm ưu đãi VIP gì không em?',
          criteria: 'Nêu rõ: Combo 5-9 buổi giảm 15% (trước VAT) nhưng KHÔNG cộng dồn Voucher Sinh Nhật/Flash Sale; đạt mốc 20 triệu (VIP Vàng) giảm thêm 5% sau Combo + miễn phí Private Room'
        },
        {
          icon: '🛡️',
          tag: '3. Xử lý chê đắt & Sợ phụ phí',
          question: 'Sao giá trị nám bên em cao hơn mấy cơ sở nhỏ lẻ gần nhà chị thế? Giá báo đã trọn gói chưa hay làm xong lại phải đưa tiền Tip cho kỹ thuật viên?',
          criteria: 'Đồng cảm nhẹ nhàng, nêu cam kết hiệu quả bằng văn bản + tinh chất mở nắp mới + Bác sĩ lên phác đồ, khẳng định trọn gói 100% & KHÔNG nhận tiền Tip'
        },
        {
          icon: '🔄',
          tag: '4. Đổi gói, Hoàn tiền & Sang tên',
          question: 'Chị mua thẻ Combo 5 buổi nhưng mới làm 1 buổi mà da bị kích ứng hoặc chị bận đi công tác muốn đổi gói / nhượng lại thẻ cho em gái dùng tiếp thì quy định thế nào em?',
          criteria: 'Nêu rõ: Đổi gói trong 07 ngày từ buổi đầu; hoàn tiền 100% nếu kích ứng cơ địa có xác nhận bác sĩ Spa; được chuyển nhượng số buổi còn lại cho người thân (miễn phí lần đầu)'
        },
        {
          icon: '🚫',
          tag: '5. Bẫy ngoài tài liệu (Chống bịa)',
          question: 'Bên em có dịch vụ tiêm Filler cằm Hàn Quốc và phẫu thuật cắt mí mắt không? Báo giá giúp chị nhé!',
          criteria: 'Tuyệt đối KHÔNG bịa giá; từ chối lịch sự đúng quy tắc Grounded và khéo léo hướng về dịch vụ chăm sóc da công nghệ cao có trong tài liệu'
        }
      ]
    }
  }

  if (ind.includes('b2b') || ind.includes('saas') || ind.includes('phần mềm') || ind.includes('công nghệ') || ind.includes('doanh nghiệp')) {
    return {
      profileTitle: `${p?.name || 'Page B2B SaaS'} (${p?.industry || 'B2B'} · KH ${p?.target_audience || 'Doanh nghiệp'})`,
      personaTone: customDesc || 'Xưng Em/Mình - Anh/Chị chuyên nghiệp, nhấn mạnh ROI/SLA và mời họp Demo kỹ thuật',
      scenarios: [
        {
          icon: '💰',
          tag: '1. Báo giá Gói & Quyền lợi',
          question: 'Báo giá chi tiết các gói sản phẩm/dịch vụ hiện có trong tài liệu và quyền lợi đi kèm của từng gói?',
          criteria: 'Trích xuất đúng bảng giá, đơn vị tính, quyền lợi tặng kèm và trích dẫn [n]'
        },
        {
          icon: '📊',
          tag: '2. Chiết khấu Số lượng / Hợp đồng',
          question: 'Chính sách chiết khấu theo quy mô/số lượng và các điều khoản loại trừ không được giảm giá là gì?',
          criteria: 'Đối soát đúng bậc thang chiết khấu và chỉ rõ hạng mục loại trừ'
        },
        {
          icon: '🛡️',
          tag: '3. Xử lý từ chối & Chi phí ẩn',
          question: 'Chi phí này có phát sinh thêm phí ẩn nào khi triển khai thực tế không? So với giải pháp giá rẻ trên thị trường thì khác biệt gì?',
          criteria: 'Giải đáp minh bạch cam kết chi phí, nêu bật giá trị cốt lõi và giữ giọng văn tư vấn B2B chuẩn mực'
        },
        {
          icon: '🔄',
          tag: '4. Cam kết Bảo hành / SLA & Hoàn tiền',
          question: 'Điều kiện bảo hành, cam kết chất lượng và quy định hoàn tiền hoặc chuyển nhượng hợp đồng cụ thể ra sao?',
          criteria: 'Trích dẫn chính xác thời hạn, điều kiện áp dụng và trường hợp miễn trừ'
        }
      ]
    }
  }

  return {
    profileTitle: p ? `${p.name} (${p.industry || 'Đa ngành'})` : 'Kho Tri Thức Chung (Tất cả Page)',
    personaTone: customDesc || 'Xưng Em - Anh/Chị lịch sự, báo số liệu chuẩn xác 100% kèm tin nhắn mẫu chốt sale tự nhiên',
    scenarios: [
      {
        icon: '💰',
        tag: '1. Báo giá & Quyền lợi đi kèm',
        question: 'Tư vấn giúp mình bảng giá chi tiết các sản phẩm/gói dịch vụ chủ lực và quà tặng kèm trong tài liệu?',
        criteria: 'Báo đúng 100% đơn giá, mã SP, quyền lợi tặng kèm và có cấu trúc 2 phần Sale-Ready'
      },
      {
        icon: '📊',
        tag: '2. Chính sách Chiết khấu & Loại trừ',
        question: 'Nếu khách mua theo Combo hoặc đi theo nhóm thì mức chiết khấu tối đa được tính như thế nào? Có sản phẩm nào không được áp dụng giảm giá không?',
        criteria: 'Tính đúng công thức cộng dồn ưu đãi và cảnh báo điều khoản loại trừ'
      },
      {
        icon: '🛡️',
        tag: '3. Xử lý khách chê đắt / Hỏi chi phí ẩn',
        question: 'Giá bên mình hơi cao so với mặt bằng chung, liệu làm xong có phát sinh thêm phụ phí hay tiền Tip gì không?',
        criteria: 'Đồng cảm, nêu bật cam kết chất lượng/không phát sinh chi phí ẩn và đặt câu hỏi chốt sale khéo léo'
      },
      {
        icon: '🔄',
        tag: '4. Bảo hành, Hoàn tiền & Đổi trả',
        question: 'Chính sách bảo hành, hoàn tiền và hỗ trợ bảo lưu/chuyển nhượng được quy định cụ thể như thế nào?',
        criteria: 'Trích dẫn chính xác thời hạn bảo hành và điều kiện hoàn tiền/bảo lưu'
      }
    ]
  }
})

/**
 * Kiểm tra xem câu trả lời của AI có chứa phần Tin nhắn mẫu gửi khách (Sale-Ready) hay không
 */
const hasSaleReadySection = (content) => {
  if (!content) return false
  return content.includes('TIN NHẮN MẪU GỬI KHÁCH') || content.includes('SALE READY') || content.includes('💬')
}

/**
 * Trích xuất riêng phần Tin nhắn mẫu gửi khách và làm sạch dấu trích dẫn [1], [2] để Sale gửi thẳng cho KH
 */
const extractSaleReadyText = (content) => {
  if (!content) return ''
  const markers = [
    /💬\s*\*\*?\[?TIN NHẮN MẪU GỬI KHÁCH[^\n]*\]?\*\*?:?/i,
    /\*\*?\[?TIN NHẮN MẪU GỬI KHÁCH[^\n]*\]?\*\*?:?/i,
    /###?\s*💬[^\n]*/i
  ]
  let extracted = content
  for (const regex of markers) {
    const match = content.match(regex)
    if (match && match.index !== undefined) {
      extracted = content.slice(match.index + match[0].length).trim()
      break
    }
  }
  return extracted
    .replace(/\s*\[\d+\]/g, '')
    .replace(/^[-*>#\s]+/, '')
    .replace(/^["“]|["”]$/g, '')
    .trim()
}

const copySaleReadyMessage = async (text, idx) => {
  try {
    const cleanMsg = extractSaleReadyText(text)
    await navigator.clipboard.writeText(cleanMsg)
    copiedSaleIndex.value = idx
    setTimeout(() => {
      copiedSaleIndex.value = -1
    }, 2200)
  } catch {
    // ignore
  }
}

/**
 * Tự động chấm điểm 4 tiêu chí chất lượng câu trả lời ngay trên giao diện:
 * 1. Chuẩn xác & Trích dẫn [n]
 * 2. Cấu trúc 2 tầng Sale-Ready
 * 3. Giọng văn Persona tự nhiên
 * 4. Khéo léo Chốt Sale (CTA)
 */
const evaluateResponseQuality = (msg) => {
  const text = msg?.content || ''
  const lower = text.toLowerCase()
  const hasCitations = (msg?.citations && msg.citations.length > 0) || /\[\d+\]/.test(text)
  const isOutOfScopeRefusal = lower.includes('không tìm thấy thông tin') || lower.includes('chưa có thông tin') || lower.includes('ngoài phạm vi')
  const hasTwoPartSaleReady = (text.includes('📋') || lower.includes('tra cứu chuẩn xác') || lower.includes('lưu ý nội bộ')) && hasSaleReadySection(text)
  const hasHumanTone = /\b(dạ|vâng|em|chị|anh|mình|bên em|nhé ạ|nha chị|ạ)\b/i.test(text)
  const hasClosingCta = /\?[\s"”*_]*$/.test(text.trim()) || /\b(đặt lịch|khung giờ|tư vấn|hỗ trợ|gửi|nhé ạ|không ạ|chưa ạ)\b/i.test(text.slice(-220))

  return [
    {
      icon: '🎯',
      label: isOutOfScopeRefusal ? 'Chống bịa 100% (Từ chối đúng)' : (hasCitations ? `Chuẩn xác (${msg?.citations?.length || 1} trích dẫn)` : 'Đã đối chiếu tài liệu'),
      passed: hasCitations || isOutOfScopeRefusal,
      tooltip: 'Kiểm tra độ chính xác số liệu và trích dẫn nguồn từ tài liệu gốc'
    },
    {
      icon: '📋',
      label: hasTwoPartSaleReady ? 'Chuẩn 2 tầng Sale-Ready' : 'Định dạng tư vấn',
      passed: hasTwoPartSaleReady || isOutOfScopeRefusal,
      tooltip: 'Tách biệt phần Tra cứu nghiệp vụ nội bộ [n] và Tin nhắn mẫu gửi khách'
    },
    {
      icon: '🎭',
      label: hasHumanTone ? 'Giọng văn giống người thật' : 'Giọng văn trung tính',
      passed: hasHumanTone,
      tooltip: 'Sử dụng xưng hô và ngữ điệu mềm mại, tự nhiên theo Page Persona'
    },
    {
      icon: '🤝',
      label: hasClosingCta ? 'Có câu hỏi gợi mở chốt Sale' : 'Thông tin thuần túy',
      passed: hasClosingCta,
      tooltip: 'Kết thúc bằng lời mời khéo léo (Call-To-Action) thúc đẩy chuyển đổi'
    }
  ]
}

const selectedDocsNames = computed(() => {
  return props.selectedDocs.map(d => d.filename).slice(0, 2).join(', ') + 
    (props.selectedDocs.length > 2 ? ` (+${props.selectedDocs.length - 2})` : '')
})

const canSend = computed(() => {
  return questionInput.value.trim().length > 0 && props.selectedDocs.length > 0 && !props.isAsking
})

const renderMarkdown = (text) => {
  if (!text) return ''
  return marked.parse(text)
}

const getDocName = (docId) => {
  const found = props.allDocs.find(d => d.document_id === docId)
  return found ? found.filename : docId
}

const autoResize = () => {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 180) + 'px'
}

const handleEnter = (e) => {
  if (e.shiftKey) return // Cho phép xuống dòng
  e.preventDefault()
  if (canSend.value) {
    submitQuestion(questionInput.value)
  }
}

const submitQuestion = (query) => {
  const q = query.trim()
  if (!q || props.selectedDocs.length === 0 || props.isAsking) return

  emit('ask-question', {
    question: q,
    documentIds: props.selectedDocs.map(d => d.document_id),
    topK: topK.value
  })

  questionInput.value = ''
  if (inputRef.value) {
    inputRef.value.style.height = 'auto'
  }

  scrollToBottom()
}

const clearChat = () => {
  emit('clear-chat')
}

const copyAnswer = async (text, idx) => {
  try {
    await navigator.clipboard.writeText(text)
    copiedIndex.value = idx
    setTimeout(() => {
      copiedIndex.value = -1
    }, 2000)
  } catch {
    // ignore
  }
}

const scrollToBottom = () => {
  nextTick(() => {
    if (messagesScrollRef.value) {
      messagesScrollRef.value.scrollTop = messagesScrollRef.value.scrollHeight
    }
  })
}

const openFanpageSimulator = () => {
  showFanpageSimulator.value = true
}

defineExpose({ scrollToBottom, openFanpageSimulator })
</script>

<style scoped>
/* NotebookLM Center/Right Floating Chat Bento Card */
.chat-container {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-bento);
  position: relative;
  overflow: hidden;
}

/* Header */
.chat-header {
  min-height: 62px;
  padding: 10px 22px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  border-bottom: 1px solid #EEF2F6;
  background: #FFFFFF;
  z-index: 10;
}

.header-left {
  display: flex;
  align-items: center;
  min-width: 0;
}

.context-indicator {
  display: flex;
  align-items: center;
  gap: 12px;
}

.pulse-sparkle {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--accent-pill-bg);
  color: var(--accent-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.header-title-row-chat {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.header-title {
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--text-main);
  line-height: 1.2;
}

.active-page-pill {
  font-size: 0.72rem;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: var(--radius-full);
  background: var(--accent-pill-bg);
  border: 1px solid #A8C7FA;
  color: var(--accent-pill-text);
}

.active-page-pill.is-global {
  background: #F0F4F9;
  border-color: var(--border-subtle);
  color: var(--text-secondary);
}

.context-subtitle {
  font-size: 0.75rem;
  color: var(--text-dim);
  margin-top: 2px;
}

.context-subtitle strong {
  color: var(--text-secondary);
}

.text-warning {
  color: #B06000;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.playbook-header-btn {
  background: #F8FAFD;
  border-color: var(--border-subtle);
  color: var(--text-main);
}

.playbook-header-btn:hover {
  background: #E8F0FE;
  border-color: #A8C7FA;
  color: var(--accent-primary);
}

.badge-mini-count {
  background: var(--accent-primary);
  color: #ffffff;
  font-size: 0.68rem;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: var(--radius-full);
  margin-left: 2px;
}

.top-k-control {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.76rem;
  color: var(--text-secondary);
  font-weight: 600;
}

.top-k-control select {
  background: #F8FAFD;
  border: 1px solid var(--border-subtle);
  color: var(--text-main);
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  font-size: 0.75rem;
  font-weight: 600;
  outline: none;
  cursor: pointer;
}

.top-k-control select:focus {
  border-color: var(--accent-primary);
}

.btn-sm {
  padding: 5px 11px;
  font-size: 0.76rem;
}

/* Messages Scroll */
.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
  display: flex;
  flex-direction: column;
  background: #FFFFFF;
}

/* Empty State */
.empty-chat-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  max-width: 720px;
  margin: 0 auto;
}

.hero-icon-bubble {
  width: 64px;
  height: 64px;
  border-radius: 20px;
  background: var(--accent-pill-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--accent-primary);
  margin-bottom: 16px;
}

.hero-title {
  font-size: 1.45rem;
  font-weight: 800;
  color: var(--text-main);
  letter-spacing: -0.02em;
  margin-bottom: 8px;
}

.hero-desc {
  font-size: 0.9rem;
  color: var(--text-secondary);
  line-height: 1.6;
  margin-bottom: 24px;
}

.hero-desc strong {
  color: var(--accent-primary);
}

.hero-desc code {
  background: #E8F0FE;
  color: var(--accent-primary);
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 700;
}

.starters-container {
  width: 100%;
}

.starters-label {
  font-size: 0.76rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--text-dim);
  margin-bottom: 10px;
  text-align: left;
}

.starters-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

.starter-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  background: #F8FAFD;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  text-align: left;
  cursor: pointer;
  transition: all 0.18s ease;
}

.starter-card:hover:not(:disabled) {
  background: #E8F0FE;
  border-color: #A8C7FA;
  transform: translateY(-2px);
  box-shadow: var(--shadow-sm);
}

.starter-card:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.starter-icon {
  font-size: 1.15rem;
}

.starter-text {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--text-main);
  line-height: 1.42;
}

/* Messages List */
.messages-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 860px;
  width: 100%;
  margin: 0 auto;
}

.message-row {
  display: flex;
  gap: 12px;
}

.row-user {
  justify-content: flex-end;
}

.row-assistant {
  justify-content: flex-start;
}

.avatar-assistant {
  width: 32px;
  height: 32px;
  min-width: 32px;
  border-radius: 10px;
  background: var(--accent-pill-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--accent-primary);
  margin-top: 4px;
}

.message-bubble {
  max-width: 84%;
  border-radius: var(--radius-lg);
  padding: 14px 18px;
  position: relative;
}

.message-bubble.user {
  background: var(--accent-primary);
  color: #ffffff;
  border-bottom-right-radius: 4px;
  box-shadow: 0 2px 8px rgba(11, 87, 208, 0.2);
}

.user-text {
  font-size: 0.93rem;
  line-height: 1.55;
  white-space: pre-wrap;
}

.message-bubble.assistant {
  background: #F8FAFD;
  border: 1px solid var(--border-subtle);
  border-top-left-radius: 4px;
  box-shadow: var(--shadow-sm);
  flex: 1;
}

.citations-section {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}

.citations-header-row {
  margin-bottom: 8px;
}

.citations-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.73rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--accent-primary);
}

.citations-grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #EEF2F6;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.74rem;
  font-weight: 600;
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  padding: 5px 11px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: all 0.15s ease;
}

.action-btn:hover {
  color: var(--text-main);
  background: #F0F4F9;
}

.action-btn-teach {
  background: #FEF7E0;
  border-color: #FDE293;
  color: #B06000;
}

.action-btn-teach:hover {
  background: #FDE293;
  color: #594300;
}

/* Applied Rules Section */
.applied-rules-container {
  margin-bottom: 12px;
}

.applied-rules-toggle {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  background: #E8F0FE;
  border: 1px solid #D3E3FD;
  border-radius: var(--radius-full);
  padding: 4px 12px;
  color: var(--accent-primary);
  font-size: 0.74rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.16s ease;
}

.applied-rules-toggle:hover {
  background: #D3E3FD;
  color: #041E49;
}

.lightning-icon {
  font-size: 0.78rem;
}

.chevron-icon {
  transition: transform 0.2s ease;
}

.rotate-180 {
  transform: rotate(180deg);
}

.applied-rules-details {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: #FFFFFF;
  border: 1px solid #D3E3FD;
  border-radius: var(--radius-md);
  padding: 10px 12px;
}

.applied-rule-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding-bottom: 6px;
  border-bottom: 1px solid #EEF2F6;
}

.applied-rule-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.applied-rule-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.category-tag-mini {
  font-size: 0.68rem;
  font-weight: 700;
  padding: 1px 7px;
  border-radius: var(--radius-full);
  background: #E8DDF8;
  color: #3F1D74;
}

.link-open-playbook {
  background: transparent;
  border: none;
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--accent-primary);
  cursor: pointer;
  text-decoration: underline;
  padding: 0;
}

.applied-rule-content {
  font-size: 0.8rem;
  color: var(--text-main);
  line-height: 1.45;
}

/* Newly Learned Rule Callout */
.newly-learned-callout {
  background: #F3E8FD;
  border: 1px solid #D7C2F5;
  border-radius: var(--radius-md);
  padding: 12px 14px;
  margin-bottom: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.callout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.callout-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.sparkle-emoji {
  font-size: 0.95rem;
}

.callout-title {
  font-size: 0.85rem;
  color: #3F1D74;
}

.callout-line {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 0.8rem;
}

.line-label {
  font-size: 0.74rem;
  font-weight: 700;
  color: #3F1D74;
  white-space: nowrap;
}

.line-val {
  color: var(--text-main);
}

.highlight-rule {
  flex-direction: column;
  background: #FFFFFF;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  border-left: 3px solid #7627BB;
}

.rule-text-val {
  font-size: 0.83rem;
  font-weight: 600;
  color: var(--text-main);
  margin-top: 2px;
  line-height: 1.45;
}

.callout-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 2px;
}

.btn-playbook-link {
  background: transparent;
  border: none;
  color: #3F1D74;
  font-size: 0.76rem;
  font-weight: 700;
  cursor: pointer;
  padding: 2px 4px;
}

.btn-playbook-link:hover {
  text-decoration: underline;
}

/* Loading state */
.loading-bubble {
  padding: 12px 16px;
}

.retrieval-indicator {
  display: flex;
  align-items: center;
  gap: 12px;
}

.typing-dots {
  display: flex;
  align-items: center;
  gap: 4px;
}

.typing-dots span {
  width: 6px;
  height: 6px;
  background: var(--accent-primary);
  border-radius: 50%;
  animation: typingBounce 1.2s infinite ease-in-out;
}

.typing-dots span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-dots span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes typingBounce {
  0%, 80%, 100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  40% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

.loading-label {
  font-size: 0.8rem;
  color: var(--text-secondary);
  font-style: italic;
}

/* Input Area (NotebookLM Floating Pill Box) */
.chat-input-wrapper {
  padding: 10px 24px 18px;
  background: #FFFFFF;
  border-top: 1px solid #EEF2F6;
}

.input-card {
  max-width: 860px;
  margin: 0 auto;
  background: #F8FAFD;
  border: 1.5px solid var(--border-subtle);
  border-radius: 20px;
  padding: 10px 16px;
  transition: all 0.18s ease;
  box-shadow: var(--shadow-sm);
}

.input-card.has-focus {
  background: #FFFFFF;
  border-color: var(--accent-primary);
  box-shadow: var(--shadow-glow);
}

.input-card.is-disabled {
  opacity: 0.6;
}

textarea {
  width: 100%;
  background: transparent;
  border: none;
  color: var(--text-main);
  font-family: var(--font-sans);
  font-size: 0.93rem;
  line-height: 1.5;
  outline: none;
  resize: none;
  min-height: 24px;
  max-height: 180px;
}

textarea::placeholder {
  color: var(--text-dim);
}

.input-bottom-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid #EEF2F6;
}

.hint-text {
  font-size: 0.72rem;
  color: var(--text-dim);
}

.btn-send {
  background: var(--accent-primary);
  color: #ffffff;
  padding: 6px 16px;
  font-size: 0.8rem;
  font-weight: 700;
  border-radius: var(--radius-full);
  display: flex;
  align-items: center;
  gap: 6px;
  box-shadow: 0 2px 6px rgba(11, 87, 208, 0.25);
}

.btn-send:hover:not(:disabled) {
  background: var(--accent-primary-hover);
  transform: translateY(-1px);
}

.btn-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

/* Q&A Tester Header Button & Drawer */
.qa-test-header-btn {
  background: #E6F4EA;
  border-color: #CEEAD6;
  color: #137333;
}

.qa-test-header-btn:hover,
.qa-test-header-btn.is-active {
  background: #CEEAD6;
  border-color: #81C995;
  color: #0D652D;
}

.badge-qa-count {
  background: #137333;
  color: #FFFFFF;
  font-size: 0.68rem;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: var(--radius-full);
}

.qa-tester-drawer {
  background: #F8FAFD;
  border-bottom: 1px solid var(--border-subtle);
  padding: 12px 22px;
  z-index: 9;
}

.qa-drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  gap: 12px;
}

.qa-drawer-title-group {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.qa-badge-pill {
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  padding: 2px 8px;
  border-radius: 6px;
  background: #CEEAD6;
  color: #0D652D;
}

.qa-persona-summary {
  font-size: 0.76rem;
  color: var(--text-secondary);
}

.qa-persona-summary strong {
  color: var(--text-main);
}

.btn-close-qa {
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 1.15rem;
  cursor: pointer;
  line-height: 1;
}

.qa-scenarios-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px;
}

.qa-scenario-chip {
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 8px 10px;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s ease;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.qa-scenario-chip:hover:not(:disabled) {
  background: #E8F0FE;
  border-color: #A8C7FA;
  transform: translateY(-1px);
}

.qa-scenario-chip:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.qa-chip-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.qa-chip-tag {
  font-size: 0.68rem;
  font-weight: 700;
  color: #137333;
}

.qa-chip-run {
  font-size: 0.66rem;
  color: var(--accent-primary);
  font-weight: 700;
}

.qa-chip-q {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-main);
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.qa-chip-crit {
  font-size: 0.66rem;
  color: var(--text-dim);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Starter Card Body */
.starter-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.starter-tag-row {
  display: flex;
  align-items: center;
}

.starter-tag {
  font-size: 0.68rem;
  font-weight: 700;
  color: #041E49;
  background: #D3E3FD;
  padding: 2px 7px;
  border-radius: 6px;
}

.starter-criteria {
  font-size: 0.7rem;
  color: var(--text-dim);
  line-height: 1.35;
}

/* Quality Scorecard Bar on Assistant Messages */
.quality-scorecard-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--border-subtle);
}

.q-score-badge {
  font-size: 0.68rem;
  font-weight: 600;
  padding: 2px 9px;
  border-radius: var(--radius-full);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.q-score-badge.is-pass {
  background: #E6F4EA;
  color: #137333;
  border: 1px solid #CEEAD6;
}

.q-score-badge.is-neutral {
  background: #F0F4F9;
  color: var(--text-secondary);
  border: 1px solid var(--border-subtle);
}

/* Copy Sale-Ready Action Button */
.action-btn-sale-copy {
  background: #E6F4EA;
  border-color: #CEEAD6;
  color: #137333;
  font-weight: 700;
}

.action-btn-sale-copy:hover {
  background: #CEEAD6;
  color: #0D652D;
}

.fb-bot-header-btn {
  background: var(--accent-pill-bg);
  border-color: #A8C7FA;
  color: var(--accent-pill-text);
  font-weight: 700;
}

.fb-bot-header-btn:hover {
  background: #C2D7FC;
  color: #041E49;
}

.badge-fb-live {
  background: var(--accent-primary);
  color: #FFFFFF;
  font-size: 0.64rem;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 6px;
}
</style>
