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
import { mockSubmitFeedback } from '../mockData'

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
    default: false
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
    let result = null
    if (props.isConnected) {
      result = await api.submitFeedback({
        question: props.question,
        faultyAnswer: props.faultyAnswer,
        userCorrection: userCorrection.value.trim(),
        documentIds: props.documentIds,
        pageId: selectedPageId.value || null,
        source: props.source || null,
        history: props.history || []
      })
    } else {
      // Giả lập xử lý học meta-rule trong Demo Mode
      await new Promise(resolve => setTimeout(resolve, 900))
      result = mockSubmitFeedback({
        question: props.question,
        faultyAnswer: props.faultyAnswer,
        userCorrection: userCorrection.value.trim(),
        documentIds: props.documentIds,
        pageId: selectedPageId.value || null,
        source: props.source || null,
        history: props.history || []
      })
      if (selectedCategory.value && result.learned_rule) {
        result.learned_rule.category = selectedCategory.value
      }
    }

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
  background-color: rgba(5, 8, 15, 0.75);
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
  background: var(--bg-secondary);
  border: 1px solid var(--border-active);
  border-radius: var(--radius-lg);
  width: 100%;
  max-width: 680px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 45px -10px rgba(0, 0, 0, 0.7), 0 0 25px rgba(99, 102, 241, 0.2);
  overflow: hidden;
}

/* Header */
.modal-header {
  padding: 20px 24px;
  border-bottom: 1px solid var(--border-subtle);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.02);
}

.modal-title-group {
  display: flex;
  align-items: center;
  gap: 12px;
}

.icon-sparkle-box {
  width: 38px;
  height: 38px;
  border-radius: var(--radius-md);
  background: rgba(99, 102, 241, 0.15);
  color: var(--accent-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(99, 102, 241, 0.3);
}

.modal-title {
  font-size: 1.12rem;
  font-weight: 600;
  color: var(--text-main);
  letter-spacing: -0.01em;
}

.modal-subtitle {
  font-size: 0.8rem;
  color: var(--text-muted);
  margin-top: 2px;
}

.btn-close {
  background: transparent;
  border: none;
  color: var(--text-muted);
  font-size: 1.5rem;
  cursor: pointer;
  line-height: 1;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  transition: all 0.15s ease;
}

.btn-close:hover:not(:disabled) {
  color: var(--text-main);
  background: rgba(255, 255, 255, 0.1);
}

/* Body */
.modal-body {
  padding: 22px 24px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.error-banner {
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  background: var(--status-failed-bg);
  border: 1px solid var(--status-failed-border);
  color: var(--status-failed-text);
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
  color: var(--text-main);
}

.required-label::after {
  content: ' *';
  color: var(--status-failed-text);
}

.text-warning-label {
  color: #fbbf24;
}

.label-with-hint {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.field-hint {
  font-size: 0.75rem;
  color: var(--text-dim);
}

.context-box {
  padding: 10px 14px;
  border-radius: var(--radius-md);
  font-size: 0.88rem;
  line-height: 1.5;
}

.question-box {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border-subtle);
  color: var(--text-main);
}

.faulty-answer-box {
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.25);
  color: #fde68a;
  max-height: 120px;
  overflow-y: auto;
}

.faulty-text {
  font-style: italic;
}

.form-textarea {
  width: 100%;
  background: var(--bg-input);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  color: var(--text-main);
  font-family: var(--font-sans);
  font-size: 0.9rem;
  resize: vertical;
  min-height: 90px;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.form-textarea:focus {
  border-color: var(--accent-primary);
  box-shadow: 0 0 0 2px var(--accent-glow);
}

.category-selection-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.form-select {
  flex: 1;
  background: var(--bg-input);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 8px 12px;
  color: var(--text-main);
  font-family: var(--font-sans);
  font-size: 0.85rem;
  outline: none;
}

.form-select:focus {
  border-color: var(--accent-primary);
}

.info-tip {
  padding: 10px 14px;
  border-radius: var(--radius-md);
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.2);
  color: #c7d2fe;
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
  color: #34d399;
  font-weight: 600;
  font-size: 0.92rem;
}

.category-pill {
  font-size: 0.72rem;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: var(--radius-full);
  background: rgba(168, 85, 247, 0.2);
  color: #d8b4fe;
  border: 1px solid rgba(168, 85, 247, 0.4);
}

.rule-details-card {
  background: var(--bg-card);
  border: 1px solid var(--border-active);
  border-radius: var(--radius-md);
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
  background: rgba(99, 102, 241, 0.1);
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  border-left: 3px solid var(--accent-primary);
}

.field-label {
  font-size: 0.76rem;
  font-weight: 600;
  color: var(--text-muted);
}

.field-content {
  font-size: 0.88rem;
  color: var(--text-main);
  line-height: 1.45;
}

.rule-text {
  font-weight: 500;
  color: #ffffff;
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
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid var(--border-subtle);
  padding: 2px 7px;
  border-radius: var(--radius-sm);
  color: #cbd5e1;
}

.regenerated-preview {
  margin-top: 8px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  max-height: 180px;
  overflow-y: auto;
}

.preview-title {
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.preview-body {
  font-size: 0.88rem;
}

/* Footer */
.modal-footer {
  padding: 16px 24px;
  border-top: 1px solid var(--border-subtle);
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  background: rgba(255, 255, 255, 0.02);
}

.btn-loading {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
