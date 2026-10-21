<template>
  <div class="modal-backdrop" @click.self="handleClose">
    <div class="modal-card teach-modal-card">
      <!-- Modal Header -->
      <div class="modal-header">
        <div class="modal-title-group">
          <div class="icon-sparkle-box">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/>
            </svg>
          </div>
          <div>
            <h3 class="modal-title">Báo sai & Dạy AI (Meta-Learning)</h3>
            <p class="modal-subtitle">AI sẽ đúc kết nguyên nhân gốc rễ và quy tắc tổng quát thay vì ghi nhớ số liệu tạm thời</p>
          </div>
        </div>
        <button class="btn-close" :disabled="isSubmitting" @click="handleClose">×</button>
      </div>

      <!-- Modal Body -->
      <div class="modal-body">
        <!-- Error alert if submit failed -->
        <div v-if="errorMessage" class="error-banner">
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <span>{{ errorMessage }}</span>
        </div>

        <!-- Success Result View: Learned Rule Preview -->
        <div v-if="learnedResult" class="result-success-container">
          <div class="success-header">
            <div class="success-badge">
              <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#34d399" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <path d="M20 6 9 17l-5-5"/>
              </svg>
              <span>AI đã tiếp thu quy tắc suy luận mới thành công!</span>
            </div>
            <span class="category-pill">{{ learnedResult.learned_rule.category }}</span>
          </div>

          <!-- Learned Meta-Rule Details -->
          <div class="rule-details-card">
            <div class="rule-field">
              <span class="field-label">⚠️ Nguyên nhân gốc rễ (Root Cause):</span>
              <p class="field-content">{{ learnedResult.learned_rule.root_cause }}</p>
            </div>

            <div class="rule-field highlight-field">
              <span class="field-label">💡 Quy tắc tổng quát (Generalized Rule):</span>
              <p class="field-content rule-text">{{ learnedResult.learned_rule.generalized_rule }}</p>
            </div>

            <div v-if="learnedResult.learned_rule.trigger_condition" class="rule-field">
              <span class="field-label">🎯 Điều kiện kích hoạt:</span>
              <p class="field-content">{{ learnedResult.learned_rule.trigger_condition }}</p>
            </div>

            <div v-if="learnedResult.learned_rule.retrieval_keywords && learnedResult.learned_rule.retrieval_keywords.length > 0" class="keywords-row">
              <span class="field-label">🏷️ Từ khóa mở rộng RAG:</span>
              <div class="keywords-list">
                <span v-for="(kw, kidx) in learnedResult.learned_rule.retrieval_keywords" :key="kidx" class="kw-tag">
                  {{ kw }}
                </span>
              </div>
            </div>
          </div>

          <!-- Regenerated Answer Preview -->
          <div class="regenerated-preview">
            <h4 class="preview-title">Câu trả lời đã tái tạo & sửa sai:</h4>
            <div class="preview-body markdown-body" v-html="renderMarkdown(learnedResult.regenerated_answer)"></div>
          </div>
        </div>

        <!-- Form Input View -->
        <div v-else class="form-container">
          <!-- Question Context -->
          <div class="context-group">
            <label class="input-label">Câu hỏi của bạn:</label>
            <div class="context-box question-box">
              {{ question || 'Không có nội dung câu hỏi' }}
            </div>
          </div>

          <!-- Faulty Answer Preview -->
          <div class="context-group">
            <label class="input-label text-warning-label">Câu trả lời AI chưa chính xác:</label>
            <div class="context-box faulty-answer-box">
              <p class="faulty-text">{{ truncatedAnswer }}</p>
            </div>
          </div>

          <!-- User Correction Input -->
          <div class="context-group">
            <div class="label-with-hint">
              <label class="input-label required-label" for="user-correction">Chỉ dẫn sửa sai & nguyên tắc đúng:</label>
              <span class="field-hint">Mô tả lý do sai và cách suy luận đúng</span>
            </div>
            <textarea 
              id="user-correction"
              v-model="userCorrection" 
              class="form-textarea"
              placeholder="Ví dụ: Khi tính giá chiết khấu đại lý, phải tính theo giá Net trước VAT và đối chiếu ngưỡng doanh số 500 triệu thay vì áp dụng luôn tỷ lệ 15%..."
              rows="4"
              :disabled="isSubmitting"
            ></textarea>
          </div>

          <!-- Category & Page Scope Override -->
          <div class="category-selection-row">
            <label class="input-label" for="category-select">Phân loại nghiệp vụ:</label>
            <select id="category-select" v-model="selectedCategory" class="form-select" :disabled="isSubmitting">
              <option value="PRICING">Bảng giá & Đơn giá (PRICING)</option>
              <option value="DISCOUNT">Chính sách Chiết khấu (DISCOUNT)</option>
              <option value="POLICY">Quy định & Hợp đồng (POLICY)</option>
              <option value="GENERAL">Nguyên tắc chung (GENERAL)</option>
            </select>
          </div>

          <div class="category-selection-row">
            <label class="input-label" for="page-scope-select">Phạm vi Page / Kênh:</label>
            <select id="page-scope-select" v-model="selectedPageId" class="form-select" :disabled="isSubmitting">
              <option value="">🌐 Quy tắc Chung (Áp dụng cho mọi Page)</option>
              <option v-for="p in pages" :key="p.id" :value="p.id">
                📂 Chỉ Page: {{ p.name }} ({{ p.industry }})
              </option>
            </select>
          </div>

          <div class="info-tip">
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="10"/>
              <line x1="12" y1="16" x2="12" y2="12"/>
              <line x1="12" y1="8" x2="12.01" y2="8"/>
            </svg>
            <span>Hệ thống sẽ tự động tổng quát hóa thành quy tắc suy luận lâu dài trong <strong>AI Playbook</strong> để không mắc lại lỗi tương tự.</span>
          </div>
        </div>
      </div>

      <!-- Modal Footer -->
      <div class="modal-footer">
        <template v-if="learnedResult">
          <button class="btn btn-primary" @click="applyAndClose">
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="20 6 9 17 4 12"/>
            </svg>
            Cập nhật câu trả lời vào hội thoại
          </button>
        </template>
        <template v-else>
          <button class="btn btn-secondary" :disabled="isSubmitting" @click="handleClose">
            Hủy bỏ
          </button>
          <button 
            class="btn btn-primary" 
            :disabled="!canSubmit || isSubmitting"
            @click="handleSubmit"
          >
            <span v-if="!isSubmitting">Dạy AI & Tạo quy tắc</span>
            <span v-else class="btn-loading">
              <span class="btn-spinner"></span>
              Đang phân tích & học quy tắc...
            </span>
          </button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { marked } from 'marked'
import { api } from '../api'

const props = defineProps({
  question: {
    type: String,
    default: ''
  },
  faultyAnswer: {
    type: String,
    default: ''
  },
  documentIds: {
    type: Array,
    default: () => []
  },
  messageIndex: {
    type: Number,
    default: -1
  },
  isConnected: {
    type: Boolean,
    default: true
  },
  pages: {
    type: Array,
    default: () => []
  },
  activePageId: {
    type: String,
    default: null
  },
  source: {
    type: String,
    default: null
  },
  history: {
    type: Array,
    default: () => []
  },
  appliedRuleIds: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['close', 'submitted'])

const userCorrection = ref('')
const selectedCategory = ref('PRICING')
const selectedPageId = ref(props.activePageId || '')
const isSubmitting = ref(false)
const errorMessage = ref('')
const learnedResult = ref(null)

const canSubmit = computed(() => {
  return userCorrection.value.trim().length >= 5
})

const truncatedAnswer = computed(() => {
  if (!props.faultyAnswer) return ''
  return props.faultyAnswer.length > 280
    ? props.faultyAnswer.substring(0, 280) + '...'
    : props.faultyAnswer
})

const renderMarkdown = (text) => {
  if (!text) return ''
  return marked.parse(text)
}

const handleSubmit = async () => {
  if (!canSubmit.value || isSubmitting.value) return

  isSubmitting.value = true
  errorMessage.value = ''

  try {
    const result = await api.submitFeedback({
      question: props.question,
      faultyAnswer: props.faultyAnswer,
      userCorrection: userCorrection.value.trim(),
      documentIds: props.documentIds,
      pageId: selectedPageId.value || null,
      source: props.source || null,
      history: props.history || [],
      appliedRuleIds: props.appliedRuleIds || []
    })

    learnedResult.value = result
  } catch (err) {
    console.error('Submit feedback error:', err)
    errorMessage.value = err.message || 'Không thể gửi phản hồi dạy AI. Vui lòng thử lại.'
  } finally {
    isSubmitting.value = false
  }
}

const applyAndClose = () => {
  if (learnedResult.value) {
    emit('submitted', {
      messageIndex: props.messageIndex,
      learnedRule: learnedResult.value.learned_rule,
      regeneratedAnswer: learnedResult.value.regenerated_answer,
      appliedRules: learnedResult.value.applied_rules || [learnedResult.value.learned_rule],
      citations: learnedResult.value.citations || [],
      customerInsight: learnedResult.value.customer_insight || null,
      activeSkills: learnedResult.value.active_skills || []
    })
  }
  emit('close')
}

const handleClose = () => {
  if (isSubmitting.value) return
  emit('close')
}
</script>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background-color: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 16px;
  animation: fadeIn 0.2s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.teach-modal-card {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 24px;
  width: 100%;
  max-width: 680px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.18), 0 4px 16px rgba(15, 23, 42, 0.06);
  overflow: hidden;
}

/* Header */
.modal-header {
  padding: 20px 24px;
  border-bottom: 1px solid #DDE3EA;
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

.icon-sparkle-box {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: #FEF7E0;
  color: #B06000;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #FDE293;
}

.modal-title {
  font-family: var(--font-display);
  font-size: 1.12rem;
  font-weight: 700;
  color: #1F1F1F;
  letter-spacing: -0.01em;
}

.modal-subtitle {
  font-size: 0.8rem;
  color: #444746;
  margin-top: 2px;
}

.btn-close {
  background: transparent;
  border: none;
  color: #747775;
  font-size: 1.5rem;
  cursor: pointer;
  line-height: 1;
  padding: 4px 8px;
  border-radius: 999px;
  transition: all 0.15s ease;
}

.btn-close:hover:not(:disabled) {
  color: #1F1F1F;
  background: #EDF2FA;
}

/* Body */
.modal-body {
  padding: 22px 24px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
  background: #FFFFFF;
}

.error-banner {
  padding: 10px 14px;
  border-radius: 12px;
  background: #FCE8E6;
  border: 1px solid #F6AEA9;
  color: #C5221F;
  font-size: 0.85rem;
  display: flex;
  align-items: center;
  gap: 8px;
}

.context-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.input-label {
  font-size: 0.82rem;
  font-weight: 600;
  color: #1F1F1F;
}

.required-label::after {
  content: ' *';
  color: #C5221F;
}

.text-warning-label {
  color: #B06000;
}

.label-with-hint {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.field-hint {
  font-size: 0.75rem;
  color: #747775;
}

.context-box {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 0.88rem;
  line-height: 1.5;
}

.question-box {
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  color: #1F1F1F;
}

.faulty-answer-box {
  background: #FEF7E0;
  border: 1px solid #FDE293;
  color: #8F4D00;
  max-height: 120px;
  overflow-y: auto;
}

.faulty-text {
  font-style: italic;
}

.form-textarea {
  width: 100%;
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  border-radius: 12px;
  padding: 12px 14px;
  color: #1F1F1F;
  font-family: var(--font-sans);
  font-size: 0.9rem;
  resize: vertical;
  min-height: 90px;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s, background-color 0.2s;
}

.form-textarea:focus {
  background: #FFFFFF;
  border-color: #0B57D0;
  box-shadow: 0 0 0 3px rgba(11, 87, 208, 0.14);
}

.category-selection-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.form-select {
  flex: 1;
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  border-radius: 12px;
  padding: 8px 12px;
  color: #1F1F1F;
  font-family: var(--font-sans);
  font-size: 0.85rem;
  outline: none;
}

.form-select:focus {
  background: #FFFFFF;
  border-color: #0B57D0;
}

.info-tip {
  padding: 10px 14px;
  border-radius: 12px;
  background: #E8F0FE;
  border: 1px solid #A8C7FA;
  color: #041E49;
  font-size: 0.8rem;
  display: flex;
  align-items: center;
  gap: 10px;
}

/* Success View */
.result-success-container {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.success-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.success-badge {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #137333;
  font-weight: 700;
  font-size: 0.92rem;
}

.category-pill {
  font-size: 0.72rem;
  font-weight: 700;
  padding: 3px 10px;
  border-radius: 999px;
  background: #E8DDF8;
  color: #4A148C;
  border: 1px solid #D0BCFF;
}

.rule-details-card {
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  border-radius: 14px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rule-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.highlight-field {
  background: #E8F0FE;
  padding: 10px 12px;
  border-radius: 10px;
  border-left: 3px solid #0B57D0;
}

.field-label {
  font-size: 0.76rem;
  font-weight: 600;
  color: #444746;
}

.field-content {
  font-size: 0.88rem;
  color: #1F1F1F;
  line-height: 1.45;
}

.rule-text {
  font-weight: 600;
  color: #041E49;
}

.keywords-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 4px;
}

.keywords-list {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.kw-tag {
  font-size: 0.72rem;
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  padding: 2px 8px;
  border-radius: 999px;
  color: #444746;
}

.regenerated-preview {
  margin-top: 8px;
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  border-radius: 12px;
  padding: 14px 16px;
  max-height: 180px;
  overflow-y: auto;
}

.preview-title {
  font-size: 0.8rem;
  font-weight: 700;
  color: #0B57D0;
  margin-bottom: 8px;
}

.preview-body {
  font-size: 0.88rem;
  color: #1F1F1F;
}

/* Footer */
.modal-footer {
  padding: 16px 24px;
  border-top: 1px solid #DDE3EA;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  background: #F8FAFD;
}

.btn-loading {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
