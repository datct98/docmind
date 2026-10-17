<template>
  <div v-if="isOpen" class="modal-backdrop" @click.self="$emit('close')">
    <div class="fb-sim-modal">
      <!-- Top Bar -->
      <div class="fb-sim-header">
        <div class="fb-sim-title-row">
          <div class="fb-icon-badge">💬</div>
          <div>
            <h3 class="fb-sim-title">
              Giả lập Bot Trực Tin Nhắn Fanpage FB
              <span class="api-pill">POST /api/bot/fanpage/message</span>
            </h3>
            <p class="fb-sim-subtitle">
              Prompt riêng · Không trích dẫn nguồn · Không Q&A nội bộ · Nhịp tư vấn tự nhiên · Tự động đẩy ca khó cho Người xử lý
            </p>
          </div>
        </div>
        <button class="btn-close-modal" @click="$emit('close')">×</button>
      </div>

      <!-- Main Split Layout -->
      <div class="fb-sim-body">
        <!-- Left: Realistic Facebook Messenger Chat View -->
        <div class="messenger-pane">
          <!-- Messenger Header -->
          <div class="messenger-topbar" :class="{ 'is-handoff': isHandedOffToHuman }">
            <div class="page-avatar-row">
              <div class="page-avatar">
                {{ (activePage?.name || 'P').charAt(0).toUpperCase() }}
              </div>
              <div>
                <div class="page-display-name">
                  {{ activePage?.name || 'Fanpage Tư Vấn Tự Động' }}
                </div>
                <div class="bot-live-status">
                  <span class="status-dot" :class="isHandedOffToHuman ? 'dot-handoff' : 'dot-active'"></span>
                  <span v-if="!isHandedOffToHuman">
                    Bot AI đang trực · Nhịp: <strong>{{ formatStageLabel(currentStage) }}</strong>
                  </span>
                  <span v-else class="handoff-status-text">
                    🟠 ĐÃ CHUYỂN NHÂN VIÊN THẬT XỬ LÝ (Bot đã tạm dừng tự động trả lời)
                  </span>
                </div>
              </div>
            </div>

            <div class="messenger-actions">
              <button class="btn-skill-sim" @click="isSkillModalOpen = true" title="Quản lý Kho Kỹ năng Tâm lý, Chốt Sale & Giao tiếp">
                🎯 Kỹ năng Agent
              </button>
              <button class="btn-reset-sim" @click="resetSimulation" title="Làm mới phiên chat của khách">
                🔄 Làm mới phiên
              </button>
            </div>
          </div>

          <!-- Handoff Banner Alert inside Messenger -->
          <div v-if="isHandedOffToHuman" class="handoff-alert-banner">
            <div class="handoff-alert-main">
              <strong>🚨 Đã đẩy cuộc hội thoại cho Nhân viên / Quản lý xử lý!</strong>
              <span class="handoff-cat-badge">{{ lastHandoffCategory }}</span>
            </div>
            <p class="handoff-reason-text">{{ lastHandoffReason }}</p>
            <button class="btn-resume-bot" @click="isHandedOffToHuman = false">
              Cho phép Bot AI tiếp tục trực lại
            </button>
          </div>

          <!-- Messenger Messages Stream -->
          <div ref="chatScrollRef" class="messenger-messages">
            <div v-if="chatItems.length === 0" class="messenger-empty">
              <p>👋 Hãy đóng vai khách hàng nhắn tin vào Fanpage ở bên dưới, hoặc bấm chọn nhanh <strong>6 Kịch bản thực chiến</strong> ở cột bên phải!</p>
            </div>

            <div
              v-for="(item, idx) in chatItems"
              :key="idx"
              class="msg-bubble-row"
              :class="item.role === 'user' ? 'is-customer' : 'is-page'"
            >
              <div v-if="item.role === 'assistant'" class="mini-page-avatar">
                {{ (activePage?.name || 'P').charAt(0).toUpperCase() }}
              </div>

              <!-- Assistant message column with bubble, active skills, applied rules, and Teach AI button -->
              <div v-if="item.role === 'assistant'" class="assistant-bubble-col">
                <div
                  class="msg-bubble assistant"
                  :class="[{ 'handoff-bubble': item.isHandoff, 'is-corrected': item.isCorrected }]"
                >
                  {{ item.text }}
                </div>

                <div
                  v-if="chatItems[idx + 1]?.role !== 'assistant'"
                  class="assistant-bubble-meta"
                >
                  <div
                    v-if="item.activeSkills && item.activeSkills.length > 0"
                    class="active-skills-badge"
                  >
                    <span class="badge-icon">⚡</span>
                    <span class="badge-label">Kỹ năng kích hoạt:</span>
                    <span
                      v-for="sk in item.activeSkills"
                      :key="sk.id || sk.code"
                      class="skill-chip-mini"
                      :title="sk.tactical_instructions"
                    >
                      {{ formatSkillCategoryIcon(sk.category) }} {{ sk.name }}
                    </span>
                  </div>

                  <div
                    v-if="item.appliedRules && item.appliedRules.length > 0"
                    class="applied-rules-badge"
                  >
                    <span class="badge-icon">🛡️</span>
                    <span class="badge-label">Đã áp dụng {{ item.appliedRules.length }} quy tắc:</span>
                    <span
                      v-for="r in item.appliedRules"
                      :key="r.id"
                      class="rule-chip"
                      :title="r.generalized_rule"
                    >
                      [{{ r.category }}] {{ r.generalized_rule ? (r.generalized_rule.length > 25 ? r.generalized_rule.substring(0, 25) + '...' : r.generalized_rule) : '' }}
                    </span>
                  </div>

                  <button
                    class="btn-teach-fb"
                    title="Báo câu trả lời này bị sai và chỉ dẫn nguyên tắc đúng cho AI"
                    @click="openTeachForMessage(idx)"
                  >
                    💬 Báo sai & Dạy AI
                  </button>
                </div>
              </div>

              <!-- Customer bubble -->
              <div
                v-else
                class="msg-bubble user"
              >
                {{ item.text }}
              </div>
            </div>

            <!-- Typing Indicator -->
            <div v-if="isTyping" class="msg-bubble-row is-page">
              <div class="mini-page-avatar">
                {{ (activePage?.name || 'P').charAt(0).toUpperCase() }}
              </div>
              <div class="msg-bubble assistant typing-indicator">
                <span></span><span></span><span></span>
              </div>
            </div>
          </div>

          <!-- Customer Input Box -->
          <div class="messenger-input-bar">
            <input
              v-model="customerInput"
              type="text"
              class="messenger-input"
              placeholder="Nhập tin nhắn trong vai khách hàng nhắn cho Fanpage..."
              :disabled="isTyping"
              @keydown.enter="sendCustomerMessage(customerInput)"
            />
            <button
              class="btn-send-fb"
              :disabled="!customerInput.trim() || isTyping"
              @click="sendCustomerMessage(customerInput)"
            >
              Gửi
            </button>
          </div>
        </div>

        <!-- Right: Telemetry, Live Customer Psychology Radar & 6 Real-World Scenarios Panel -->
        <div class="telemetry-pane">
          <!-- Telemetry Status Card -->
          <div class="telemetry-card">
            <div class="telemetry-header">
              <span>📡 Trạng Thái Điều Phối Bot (AstrBot Pipeline)</span>
              <span class="conf-badge">Độ tin cậy: {{ Math.round(lastConfidence * 100) }}%</span>
            </div>

            <div class="stage-stepper">
              <div class="step-item" :class="{ active: currentStage === 'DISCOVERY' }">
                1. Thấu cảm & Hỏi nhu cầu
              </div>
              <div class="step-item" :class="{ active: currentStage === 'VALUE_CONSULTATION' }">
                2. Tư vấn Giá trị
              </div>
              <div class="step-item" :class="{ active: currentStage === 'SOFT_BOOKING_CLOSE' }">
                3. Điều hướng Đặt lịch
              </div>
              <div class="step-item step-handoff" :class="{ active: isHandedOffToHuman || currentStage === 'HUMAN_HANDOFF' }">
                🟠 Chuyển Người Xử Lý
              </div>
            </div>

            <!-- Lead & Booking Capture Box -->
            <div class="lead-capture-box" :class="{ 'has-lead': capturedLead?.has_buying_or_booking_signal }">
              <div class="lead-box-title">
                🎯 Tín hiệu Chốt Sale / Đặt lịch thu thập tự động:
              </div>
              <div v-if="capturedLead?.has_buying_or_booking_signal" class="lead-details">
                <span v-if="capturedLead.phone_number" class="lead-pill">📞 SĐT: {{ capturedLead.phone_number }}</span>
                <span v-if="capturedLead.preferred_time" class="lead-pill">⏰ Hẹn: {{ capturedLead.preferred_time }}</span>
                <span class="lead-note">{{ capturedLead.summary_note }}</span>
              </div>
              <div v-else class="lead-empty">
                Chưa phát hiện SĐT hoặc lịch hẹn cụ thể từ khách hàng.
              </div>
            </div>
          </div>

          <!-- Live Customer Psychology & Insight Radar Card -->
          <div class="insight-radar-card">
            <div class="insight-radar-header">
              <div class="insight-title-row">
                <span>🧠 Radar Thấu hiểu Tâm lý &amp; Insight Khách hàng</span>
              </div>
              <span v-if="currentInsight" class="honorific-pill">
                Xưng hô: <strong>Em – {{ currentInsight.honorific || 'Chị' }}</strong>
              </span>
            </div>

            <div v-if="!currentInsight" class="insight-empty">
              Hãy gửi tin nhắn đầu tiên để AI tự động đọc vị chân dung tâm lý, rào cản ngầm, nhiệt độ mua hàng và kích hoạt Kỹ năng Chuyên gia tương ứng.
            </div>

            <div v-else class="insight-body">
              <!-- Archetype & Buying Temperature -->
              <div class="insight-top-grid">
                <div class="archetype-box">
                  <span class="insight-sub-label">Chân dung tâm lý hiện tại:</span>
                  <div class="archetype-value">{{ currentInsight.archetype_label }}</div>
                </div>

                <div class="temp-box">
                  <div class="temp-label-row">
                    <span class="insight-sub-label">Nhiệt độ mua hàng:</span>
                    <span class="temp-badge" :class="'temp-' + (currentInsight.temperature_label || 'WARM').toLowerCase()">
                      {{ currentInsight.buying_temperature }}% · {{ currentInsight.temperature_label }}
                    </span>
                  </div>
                  <div class="temp-bar-track">
                    <div
                      class="temp-bar-fill"
                      :class="'fill-' + (currentInsight.temperature_label || 'WARM').toLowerCase()"
                      :style="{ width: Math.min(100, Math.max(8, currentInsight.buying_temperature || 40)) + '%' }"
                    ></div>
                  </div>
                </div>
              </div>

              <!-- Accumulated Pains & Facts -->
              <div v-if="currentInsight.extracted_pains && currentInsight.extracted_pains.length" class="insight-block">
                <span class="insight-sub-label">📌 Nỗi đau &amp; Dữ kiện khách đã chia sẻ (Ghi nhớ xuyên suốt):</span>
                <div class="insight-chips">
                  <span
                    v-for="(pain, pIdx) in currentInsight.extracted_pains"
                    :key="pIdx"
                    class="insight-chip chip-pain"
                  >
                    {{ pain }}
                  </span>
                </div>
              </div>

              <!-- Hidden Concerns -->
              <div v-if="currentInsight.hidden_concerns && currentInsight.hidden_concerns.length" class="insight-block">
                <span class="insight-sub-label">🔍 Rào cản tâm lý &amp; Nỗi sợ ngầm đọc vị được:</span>
                <div class="insight-chips">
                  <span
                    v-for="(concern, cIdx) in currentInsight.hidden_concerns"
                    :key="cIdx"
                    class="insight-chip chip-concern"
                  >
                    {{ concern }}
                  </span>
                </div>
              </div>

              <!-- Recommended Strategy -->
              <div v-if="currentInsight.recommended_strategy" class="strategy-callout">
                <span class="strategy-title">💡 Chiến thuật AI đang thực thi:</span>
                <p class="strategy-text">{{ currentInsight.recommended_strategy }}</p>
              </div>

              <!-- Active Skills Engaged -->
              <div v-if="latestActiveSkills && latestActiveSkills.length" class="insight-block">
                <div class="skills-row-header">
                  <span class="insight-sub-label">⚡ Top Kỹ năng Chuyên gia đang kích hoạt ({{ latestActiveSkills.length }}):</span>
                  <button class="btn-link-mini" @click="isSkillModalOpen = true">Cấu hình Skills →</button>
                </div>
                <div class="insight-chips">
                  <span
                    v-for="sk in latestActiveSkills"
                    :key="sk.id || sk.code"
                    class="insight-chip chip-skill"
                    :title="sk.tactical_instructions"
                  >
                    {{ formatSkillCategoryIcon(sk.category) }} {{ sk.name }}
                  </span>
                </div>
              </div>
            </div>
          </div>

          <!-- 6 Real-World Fanpage Test Scenarios -->
          <div class="scenarios-section">
            <div class="scenarios-title">
              ⚡ 6 Tình huống Thực tế trên Fanpage (Bấm theo thứ tự 1 → 6 để test):
            </div>
            <div class="scenarios-list">
              <button
                v-for="(sc, sIdx) in fanpageScenarios"
                :key="sIdx"
                class="sc-btn"
                :disabled="isTyping"
                @click="sendCustomerMessage(sc.message)"
              >
                <div class="sc-top">
                  <span class="sc-badge" :class="sc.type">{{ sc.label }}</span>
                  <span class="sc-try">Gửi thử →</span>
                </div>
                <div class="sc-msg">"{{ sc.message }}"</div>
                <div class="sc-expect">Kỳ vọng: {{ sc.expected }}</div>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Teach AI Modal Integration -->
      <TeachAiModal
        v-if="teachingTarget"
        :question="teachingTarget.question"
        :faulty-answer="teachingTarget.faultyAnswer"
        :document-ids="teachingTarget.documentIds"
        :message-index="teachingTarget.messageIndex"
        :is-connected="isConnected"
        :pages="pages"
        :active-page-id="teachingTarget.activePageId"
        :history="teachingTarget.history || []"
        source="FANPAGE_BOT"
        @close="teachingTarget = null"
        @submitted="handleTeachSubmitted"
      />

      <!-- Agent Skill Registry Modal -->
      <AgentSkillModal
        v-if="isSkillModalOpen"
        :demo-mode="!isConnected"
        :pages="pages"
        :active-page-id="activePage?.id || null"
        @close="isSkillModalOpen = false"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { api } from '../api'
import { mockFanpageBotReply } from '../mockData'
import TeachAiModal from './TeachAiModal.vue'
import AgentSkillModal from './AgentSkillModal.vue'

const props = defineProps({
  isOpen: {
    type: Boolean,
    default: false
  },
  activePage: {
    type: Object,
    default: null
  },
  pages: {
    type: Array,
    default: () => []
  },
  selectedDocs: {
    type: Array,
    default: () => []
  },
  isConnected: {
    type: Boolean,
    default: false
  }
})

defineEmits(['close'])

const customerInput = ref('')
const chatItems = ref([])
const conversationHistory = ref([])
const isTyping = ref(false)
const currentStage = ref('DISCOVERY')
const lastConfidence = ref(0.9)
const isHandedOffToHuman = ref(false)
const lastHandoffCategory = ref(null)
const lastHandoffReason = ref(null)
const capturedLead = ref(null)
const currentInsight = ref(null)
const latestActiveSkills = ref([])
const isSkillModalOpen = ref(false)
const chatScrollRef = ref(null)

const formatSkillCategoryIcon = (category) => {
  if (category === 'PSYCHOLOGY') return '🧠'
  if (category === 'COMMUNICATION') return '💬'
  return '🎯'
}

const fanpageScenarios = [
  {
    label: '1. Nhịp Làm Quen (Không vội vã)',
    type: 'normal',
    message: 'Chào shop, da chị dạo này có mấy vết nám hai bên gò má thì bên mình có liệu trình nào cải thiện an toàn không em?',
    expected: 'Trả lời nhẹ nhàng, giới thiệu Phác đồ Trị Nám Laser Pico và hỏi thăm tình trạng da, chưa vội ép đặt lịch'
  },
  {
    label: '2. Hỏi Câu Cụt Nối Tiếp (Nhớ ngữ cảnh)',
    type: 'normal',
    message: 'Giá sao em? Có cam kết bảo hành gì không?',
    expected: 'Tự hiểu khách đang hỏi tiếp gói Trị Nám ở lượt 1; báo giá 4.500.000đ/buổi kèm BH tái phát 12 tháng bằng văn bản'
  },
  {
    label: '3. Khách Chê Đắt & Sợ Phụ Phí / Tip',
    type: 'normal',
    message: 'Sao giá cao hơn mấy spa nhỏ gần nhà chị thế? Làm xong có bắt mua thêm mỹ phẩm hay phải đưa tiền Tip cho nhân viên không em?',
    expected: 'Đồng cảm khéo léo, cam kết giá trọn gói 100% + không nhận tiền Tip + gợi ý Combo giảm 10-15%'
  },
  {
    label: '4. Khách Chốt Đặt Lịch & Để Lại SĐT',
    type: 'lead',
    message: 'Ok em tư vấn dễ thương quá, vậy đặt lịch cho chị chiều thứ 7 tuần này qua bác sĩ soi da trước nhé, sđt chị là 0912.345.678',
    expected: 'Tự động bắt SĐT 0912345678 & khung giờ "chiều thứ 7", xác nhận lịch hẹn và bật cờ báo Sale gọi xác nhận'
  },
  {
    label: '5. Hỏi Dịch Vụ Ngoài Tài Liệu (Đẩy cho Người)',
    type: 'handoff',
    message: 'À bên em có nhận tiêm Filler cằm Hàn Quốc và phẫu thuật cắt mí mắt không? Báo giá giúp chị luôn nhé!',
    expected: 'Tuyệt đối KHÔNG bịa giá; bật cờ needs_human_handoff = true (UNGROUNDED_OR_UNKNOWN) và hẹn chuyên viên tư vấn'
  },
  {
    label: '6. Ca Khó: Báo Kích Ứng / Đòi Gặp Quản Lý',
    type: 'handoff',
    message: 'Em ơi hôm qua bạn chị làm liệu trình về da bị kích ứng đỏ rát quá, cho chị gặp quản lý hoặc bác sĩ xử lý ngay!',
    expected: 'Dừng AI ngay lập tức (Pre-LLM Handoff), xin lỗi chân thành và chuyển thẳng cho Quản lý / Bác sĩ xử lý'
  }
]

const formatStageLabel = (stage) => {
  const map = {
    DISCOVERY: '1. Thấu cảm & Khai thác nhu cầu',
    VALUE_CONSULTATION: '2. Tư vấn Giá trị & Giải tỏa băn khoăn',
    SOFT_BOOKING_CLOSE: '3. Điều hướng Đặt lịch / Chốt Sale',
    HUMAN_HANDOFF: 'Chuyển Nhân Viên Thật'
  }
  return map[stage] || stage
}

const scrollToBottom = () => {
  nextTick(() => {
    if (chatScrollRef.value) {
      chatScrollRef.value.scrollTop = chatScrollRef.value.scrollHeight
    }
  })
}

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))

const sendCustomerMessage = async (rawText) => {
  const text = (rawText || '').trim()
  if (!text || isTyping.value) return

  customerInput.value = ''
  chatItems.value.push({ role: 'user', text })
  scrollToBottom()

  isTyping.value = true
  try {
    let response
    const docIds = props.selectedDocs.map(d => d.document_id)
    if (props.isConnected) {
      response = await api.sendFanpageBotMessage({
        pageId: props.activePage?.id || null,
        senderId: 'fb-customer-demo',
        customerName: null,
        message: text,
        history: conversationHistory.value,
        documentIds: docIds,
        topK: 5
      })
    } else {
      await sleep(500)
      response = mockFanpageBotReply({
        message: text,
        history: conversationHistory.value,
        customerName: null,
        pageId: props.activePage?.id || null
      })
    }

    // Cập nhật lịch sử hội thoại
    conversationHistory.value.push({ role: 'user', content: text })
    conversationHistory.value.push({ role: 'assistant', content: response.reply_text })

    currentStage.value = response.sales_stage || 'DISCOVERY'
    lastConfidence.value = response.confidence_score ?? 0.85

    if (response.customer_insight) {
      currentInsight.value = response.customer_insight
    }
    if (response.active_skills) {
      latestActiveSkills.value = response.active_skills
    }

    if (response.extracted_lead && response.extracted_lead.has_buying_or_booking_signal) {
      capturedLead.value = response.extracted_lead
    }

    if (response.needs_human_handoff) {
      isHandedOffToHuman.value = true
      lastHandoffCategory.value = response.handoff_category || 'HUMAN_HANDOFF'
      lastHandoffReason.value = response.handoff_reason || 'Tình huống cần chuyên viên trực tiếp xử lý.'
    }

    // Hiển thị lần lượt từng bong bóng chat như nhân viên thật đang nhắn trên Messenger
    const bubbles = response.reply_bubbles && response.reply_bubbles.length > 0
      ? response.reply_bubbles
      : [{ text: response.reply_text, typing_delay_ms: 700 }]

    const appliedRules = response.applied_rules || []
    const activeSkills = response.active_skills || []

    for (let i = 0; i < bubbles.length; i++) {
      if (i > 0) {
        isTyping.value = true
        scrollToBottom()
        await sleep(Math.min(900, bubbles[i].typing_delay_ms || 650))
      }
      chatItems.value.push({
        role: 'assistant',
        text: bubbles[i].text,
        isHandoff: response.needs_human_handoff,
        appliedRules: i === bubbles.length - 1 ? appliedRules : [],
        activeSkills: i === bubbles.length - 1 ? activeSkills : []
      })
      scrollToBottom()
    }
  } catch (err) {
    chatItems.value.push({
      role: 'assistant',
      text: 'Dạ hệ thống đang chuyển kết nối tới Chuyên viên tư vấn trực tiếp của Page để hỗ trợ Chị ngay ạ!',
      isHandoff: true,
      appliedRules: [],
      activeSkills: []
    })
    isHandedOffToHuman.value = true
    lastHandoffCategory.value = 'CONNECTION_FALLBACK'
    lastHandoffReason.value = err.message || 'Lỗi kết nối API'
  } finally {
    isTyping.value = false
    scrollToBottom()
  }
}

const teachingTarget = ref(null)

const buildTurnHistoryFromChatItems = (itemsSlice) => {
  const turns = []
  for (const item of itemsSlice) {
    if (!item || !item.text) continue
    const last = turns[turns.length - 1]
    if (last && last.role === item.role) {
      last.content = `${last.content}\n\n${item.text}`
    } else {
      turns.push({ role: item.role, content: item.text })
    }
  }
  return turns.slice(-8)
}

const openTeachForMessage = (assistantIdx) => {
  let turnStartIdx = assistantIdx
  while (turnStartIdx > 0 && chatItems.value[turnStartIdx - 1]?.role === 'assistant') {
    turnStartIdx--
  }
  let turnEndIdx = assistantIdx
  while (turnEndIdx + 1 < chatItems.value.length && chatItems.value[turnEndIdx + 1]?.role === 'assistant') {
    turnEndIdx++
  }

  let customerQuestion = ''
  let questionIdx = -1
  for (let i = turnStartIdx - 1; i >= 0; i--) {
    if (chatItems.value[i].role === 'user') {
      customerQuestion = chatItems.value[i].text
      questionIdx = i
      break
    }
  }
  if (!customerQuestion) {
    customerQuestion = 'Tư vấn trên Fanpage'
  }

  const faultyAnswer = chatItems.value
    .slice(turnStartIdx, turnEndIdx + 1)
    .map(item => item.text || '')
    .filter(Boolean)
    .join('\n\n')
  const priorHistory = questionIdx > 0
    ? buildTurnHistoryFromChatItems(chatItems.value.slice(0, questionIdx))
    : []
  const docIds = (props.selectedDocs || []).map(d => d.document_id)

  teachingTarget.value = {
    messageIndex: assistantIdx,
    turnStartIndex: turnStartIdx,
    turnEndIndex: turnEndIdx,
    question: customerQuestion,
    faultyAnswer: faultyAnswer,
    documentIds: docIds,
    activePageId: props.activePage?.id || null,
    history: priorHistory
  }
}

const handleTeachSubmitted = ({ messageIndex, learnedRule, regeneratedAnswer, appliedRules, customerInsight, activeSkills }) => {
  const startIdx = teachingTarget.value?.turnStartIndex ?? messageIndex
  const endIdx = teachingTarget.value?.turnEndIndex ?? messageIndex

  if (startIdx >= 0 && startIdx < chatItems.value.length) {
    const paragraphs = (regeneratedAnswer || '')
      .split(/\n{2,}/)
      .map(p => p.trim())
      .filter(Boolean)
    const finalParagraphs = paragraphs.length > 0 ? paragraphs : [regeneratedAnswer || '']
    const resolvedRules = appliedRules || (learnedRule ? [learnedRule] : [])
    const resolvedSkills = activeSkills && activeSkills.length > 0
      ? activeSkills
      : (chatItems.value[endIdx]?.activeSkills || [])

    const replacementBubbles = finalParagraphs.map((para, idx) => ({
      role: 'assistant',
      text: para,
      isHandoff: false,
      isCorrected: true,
      appliedRules: idx === finalParagraphs.length - 1 ? resolvedRules : [],
      activeSkills: idx === finalParagraphs.length - 1 ? resolvedSkills : []
    }))

    chatItems.value.splice(startIdx, endIdx - startIdx + 1, ...replacementBubbles)

    if (activeSkills && activeSkills.length > 0) {
      latestActiveSkills.value = activeSkills
    }
  }
  if (customerInsight) {
    currentInsight.value = customerInsight
  }

  // Đồng bộ lại conversationHistory từ chatItems để các lượt tiếp theo nhận đầy đủ lịch sử đã sửa
  conversationHistory.value = buildTurnHistoryFromChatItems(chatItems.value)

  teachingTarget.value = null
}

const resetSimulation = () => {
  chatItems.value = []
  conversationHistory.value = []
  currentStage.value = 'DISCOVERY'
  lastConfidence.value = 0.9
  isHandedOffToHuman.value = false
  lastHandoffCategory.value = null
  lastHandoffReason.value = null
  capturedLead.value = null
  currentInsight.value = null
  latestActiveSkills.value = []
  teachingTarget.value = null
}
</script>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(5, 8, 18, 0.78);
  backdrop-filter: blur(8px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.fb-sim-modal {
  width: 100%;
  max-width: 1120px;
  height: 86vh;
  background: #0f172a;
  border: 1px solid rgba(99, 102, 241, 0.35);
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.65);
}

.fb-sim-header {
  padding: 14px 20px;
  background: rgba(30, 41, 59, 0.85);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.fb-sim-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.fb-icon-badge {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: linear-gradient(135deg, #2563eb, #4f46e5);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.2rem;
}

.fb-sim-title {
  font-size: 1rem;
  font-weight: 700;
  color: #f8fafc;
  display: flex;
  align-items: center;
  gap: 10px;
}

.api-pill {
  font-size: 0.7rem;
  font-family: monospace;
  background: rgba(16, 185, 129, 0.15);
  color: #6ee7b7;
  border: 1px solid rgba(16, 185, 129, 0.35);
  padding: 2px 8px;
  border-radius: 6px;
}

.fb-sim-subtitle {
  font-size: 0.76rem;
  color: #94a3b8;
  margin-top: 2px;
}

.btn-close-modal {
  background: none;
  border: none;
  color: #94a3b8;
  font-size: 1.5rem;
  cursor: pointer;
}

.fb-sim-body {
  flex: 1;
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  min-height: 0;
}

/* Left Messenger Pane */
.messenger-pane {
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  background: #0b1120;
  min-height: 0;
}

.messenger-topbar {
  padding: 12px 16px;
  background: rgba(30, 41, 59, 0.65);
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.messenger-topbar.is-handoff {
  background: rgba(245, 158, 11, 0.14);
  border-bottom-color: rgba(245, 158, 11, 0.35);
}

.page-avatar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ec4899, #8b5cf6);
  color: #fff;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.page-display-name {
  font-size: 0.88rem;
  font-weight: 700;
  color: #f1f5f9;
}

.bot-live-status {
  font-size: 0.74rem;
  color: #cbd5e1;
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot-active {
  background: #10b981;
  box-shadow: 0 0 8px #10b981;
}

.dot-handoff {
  background: #f59e0b;
  box-shadow: 0 0 8px #f59e0b;
}

.handoff-status-text {
  color: #fcd34d;
  font-weight: 600;
}

.btn-reset-sim {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: #cbd5e1;
  font-size: 0.74rem;
  padding: 5px 10px;
  border-radius: 6px;
  cursor: pointer;
}

.handoff-alert-banner {
  background: rgba(245, 158, 11, 0.12);
  border-bottom: 1px solid rgba(245, 158, 11, 0.3);
  padding: 10px 16px;
  font-size: 0.78rem;
  color: #fde68a;
}

.handoff-alert-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.handoff-cat-badge {
  background: rgba(245, 158, 11, 0.25);
  color: #fef08a;
  font-size: 0.68rem;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 4px;
}

.handoff-reason-text {
  color: #f8fafc;
  margin-bottom: 6px;
}

.btn-resume-bot {
  background: rgba(16, 185, 129, 0.2);
  border: 1px solid rgba(16, 185, 129, 0.45);
  color: #6ee7b7;
  font-size: 0.72rem;
  padding: 3px 9px;
  border-radius: 4px;
  cursor: pointer;
}

.messenger-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.messenger-empty {
  margin: auto;
  text-align: center;
  max-width: 380px;
  color: #64748b;
  font-size: 0.84rem;
  line-height: 1.5;
}

.msg-bubble-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.msg-bubble-row.is-customer {
  justify-content: flex-end;
}

.msg-bubble-row.is-page {
  justify-content: flex-start;
}

.mini-page-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ec4899, #8b5cf6);
  color: #fff;
  font-size: 0.72rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.assistant-bubble-col {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 82%;
}

.assistant-bubble-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-left: 2px;
}

.applied-rules-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  background: rgba(99, 102, 241, 0.12);
  border: 1px solid rgba(99, 102, 241, 0.35);
  border-radius: 6px;
  font-size: 0.72rem;
  color: #c7d2fe;
}

.badge-icon {
  font-size: 0.75rem;
}

.badge-label {
  font-weight: 600;
  color: #a5b4fc;
}

.rule-chip {
  background: rgba(255, 255, 255, 0.08);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 0.7rem;
  color: #f1f5f9;
}

.btn-teach-fb {
  background: rgba(245, 158, 11, 0.12);
  border: 1px solid rgba(245, 158, 11, 0.35);
  color: #fde68a;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 0.72rem;
  cursor: pointer;
  transition: all 0.15s ease;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.btn-teach-fb:hover {
  background: rgba(245, 158, 11, 0.22);
  border-color: rgba(245, 158, 11, 0.6);
  color: #fff;
}

.msg-bubble {
  padding: 10px 14px;
  border-radius: 18px;
  font-size: 0.86rem;
  line-height: 1.48;
  white-space: pre-wrap;
}

.msg-bubble.user {
  max-width: 78%;
  background: #2563eb;
  color: #ffffff;
  border-bottom-right-radius: 4px;
}

.msg-bubble.assistant {
  background: #1e293b;
  color: #f1f5f9;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-bottom-left-radius: 4px;
}

.msg-bubble.handoff-bubble {
  border-color: rgba(245, 158, 11, 0.45);
  background: rgba(30, 41, 59, 0.95);
}

.msg-bubble.is-corrected {
  border-color: rgba(16, 185, 129, 0.5);
  box-shadow: 0 0 10px rgba(16, 185, 129, 0.15);
}

.typing-indicator {
  display: inline-flex;
  gap: 4px;
  padding: 12px 16px;
}

.typing-indicator span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #94a3b8;
  animation: fbBounce 1.2s infinite;
}

.typing-indicator span:nth-child(2) {
  animation-delay: 0.2s;
}
.typing-indicator span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes fbBounce {
  0%, 80%, 100% { transform: translateY(0); opacity: 0.4; }
  40% { transform: translateY(-4px); opacity: 1; }
}

.messenger-input-bar {
  padding: 12px 14px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  gap: 8px;
  background: #0f172a;
}

.messenger-input {
  flex: 1;
  background: #1e293b;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  padding: 9px 16px;
  color: #f8fafc;
  font-size: 0.85rem;
  outline: none;
}

.messenger-input:focus {
  border-color: #3b82f6;
}

.btn-send-fb {
  background: #2563eb;
  color: #ffffff;
  border: none;
  border-radius: 999px;
  padding: 0 18px;
  font-weight: 600;
  font-size: 0.84rem;
  cursor: pointer;
}

.btn-send-fb:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* Right Telemetry & Scenarios Pane */
.telemetry-pane {
  padding: 16px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: #0f172a;
}

.telemetry-card {
  background: rgba(30, 41, 59, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.telemetry-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 0.78rem;
  font-weight: 700;
  color: #e2e8f0;
}

.conf-badge {
  background: rgba(99, 102, 241, 0.18);
  color: #a5b4fc;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 0.7rem;
}

.stage-stepper {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
}

.step-item {
  font-size: 0.72rem;
  padding: 6px 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.07);
  color: #64748b;
}

.step-item.active {
  background: rgba(16, 185, 129, 0.16);
  border-color: rgba(16, 185, 129, 0.45);
  color: #6ee7b7;
  font-weight: 700;
}

.step-item.step-handoff.active {
  background: rgba(245, 158, 11, 0.2);
  border-color: rgba(245, 158, 11, 0.5);
  color: #fcd34d;
}

.lead-capture-box {
  background: rgba(15, 23, 42, 0.65);
  border: 1px dashed rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  padding: 8px 10px;
}

.lead-capture-box.has-lead {
  border-style: solid;
  border-color: rgba(16, 185, 129, 0.45);
  background: rgba(16, 185, 129, 0.08);
}

.lead-box-title {
  font-size: 0.72rem;
  font-weight: 700;
  color: #cbd5e1;
  margin-bottom: 4px;
}

.lead-details {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.lead-pill {
  background: rgba(16, 185, 129, 0.22);
  color: #6ee7b7;
  font-size: 0.72rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}

.lead-note {
  font-size: 0.72rem;
  color: #e2e8f0;
}

.lead-empty {
  font-size: 0.72rem;
  color: #64748b;
}

.scenarios-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.scenarios-title {
  font-size: 0.76rem;
  font-weight: 700;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.scenarios-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sc-btn {
  background: rgba(30, 41, 59, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  padding: 9px 11px;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s ease;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sc-btn:hover:not(:disabled) {
  border-color: rgba(59, 130, 246, 0.5);
  background: rgba(30, 41, 59, 0.9);
  transform: translateY(-1px);
}

.sc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sc-badge {
  font-size: 0.7rem;
  font-weight: 700;
  padding: 1px 7px;
  border-radius: 4px;
}

.sc-badge.normal {
  background: rgba(59, 130, 246, 0.16);
  color: #93c5fd;
}

.sc-badge.lead {
  background: rgba(16, 185, 129, 0.18);
  color: #6ee7b7;
}

.sc-badge.handoff {
  background: rgba(245, 158, 11, 0.18);
  color: #fcd34d;
}

.sc-try {
  font-size: 0.68rem;
  color: #a5b4fc;
  font-weight: 600;
}

.sc-msg {
  font-size: 0.78rem;
  color: #f1f5f9;
  line-height: 1.35;
}

.sc-expect {
  font-size: 0.68rem;
  color: #94a3b8;
  line-height: 1.3;
}

.messenger-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-skill-sim {
  padding: 5px 10px;
  border-radius: 8px;
  font-size: 0.74rem;
  font-weight: 700;
  border: 1px solid rgba(168, 85, 247, 0.45);
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.22), rgba(99, 102, 241, 0.16));
  color: #e9d5ff;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-skill-sim:hover {
  background: linear-gradient(135deg, #8b5cf6, #6366f1);
  color: #fff;
}

.active-skills-badge {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  padding: 4px 8px;
  border-radius: 8px;
  background: rgba(139, 92, 246, 0.12);
  border: 1px solid rgba(139, 92, 246, 0.28);
  font-size: 0.7rem;
}

.skill-chip-mini {
  padding: 1px 6px;
  border-radius: 5px;
  background: rgba(15, 23, 42, 0.65);
  color: #ddd6fe;
  font-weight: 600;
}

/* Customer Psychology & Insight Radar Card */
.insight-radar-card {
  background: linear-gradient(160deg, rgba(30, 27, 75, 0.65), rgba(15, 23, 42, 0.85));
  border: 1px solid rgba(139, 92, 246, 0.35);
  border-radius: 12px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.insight-radar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.insight-title-row {
  font-size: 0.8rem;
  font-weight: 700;
  color: #e9d5ff;
}

.honorific-pill {
  font-size: 0.7rem;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(59, 130, 246, 0.18);
  color: #bfdbfe;
  border: 1px solid rgba(59, 130, 246, 0.3);
}

.insight-empty {
  font-size: 0.74rem;
  color: #94a3b8;
  line-height: 1.4;
  padding: 6px 0;
}

.insight-body {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.insight-top-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: 8px;
}

.archetype-box,
.temp-box {
  background: rgba(15, 23, 42, 0.65);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 7px 9px;
}

.insight-sub-label {
  font-size: 0.69rem;
  font-weight: 700;
  color: #94a3b8;
  display: block;
  margin-bottom: 3px;
}

.archetype-value {
  font-size: 0.76rem;
  font-weight: 700;
  color: #f3e8ff;
  line-height: 1.3;
}

.temp-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 5px;
}

.temp-badge {
  font-size: 0.68rem;
  font-weight: 800;
  padding: 1px 6px;
  border-radius: 999px;
}

.temp-hot {
  background: rgba(239, 68, 68, 0.22);
  color: #fca5a5;
}

.temp-warm {
  background: rgba(245, 158, 11, 0.22);
  color: #fde68a;
}

.temp-cold {
  background: rgba(59, 130, 246, 0.22);
  color: #93c5fd;
}

.temp-bar-track {
  width: 100%;
  height: 7px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.temp-bar-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.35s ease;
}

.fill-hot {
  background: linear-gradient(90deg, #f59e0b, #ef4444);
}

.fill-warm {
  background: linear-gradient(90deg, #3b82f6, #f59e0b);
}

.fill-cold {
  background: linear-gradient(90deg, #64748b, #3b82f6);
}

.insight-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.insight-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.insight-chip {
  font-size: 0.7rem;
  padding: 3px 8px;
  border-radius: 6px;
  line-height: 1.3;
}

.chip-pain {
  background: rgba(56, 189, 248, 0.12);
  color: #bae6fd;
  border: 1px solid rgba(56, 189, 248, 0.25);
}

.chip-concern {
  background: rgba(245, 158, 11, 0.12);
  color: #fde68a;
  border: 1px solid rgba(245, 158, 11, 0.28);
}

.chip-skill {
  background: rgba(168, 85, 247, 0.16);
  color: #e9d5ff;
  border: 1px solid rgba(168, 85, 247, 0.32);
  font-weight: 600;
}

.strategy-callout {
  background: rgba(16, 185, 129, 0.1);
  border: 1px solid rgba(16, 185, 129, 0.28);
  border-radius: 8px;
  padding: 7px 9px;
}

.strategy-title {
  font-size: 0.7rem;
  font-weight: 700;
  color: #6ee7b7;
  display: block;
  margin-bottom: 2px;
}

.strategy-text {
  margin: 0;
  font-size: 0.73rem;
  color: #d1fae5;
  line-height: 1.38;
}

.skills-row-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.btn-link-mini {
  background: none;
  border: none;
  color: #c4b5fd;
  font-size: 0.69rem;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}

.btn-link-mini:hover {
  text-decoration: underline;
  color: #fff;
}
</style>
