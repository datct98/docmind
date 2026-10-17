<template>
  <div v-if="doc" class="modal-backdrop" @click.self="$emit('close')">
    <div class="modal-card">
      <div class="modal-header">
        <div class="modal-title-group">
          <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="title-icon">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
            <polyline points="14 2 14 8 20 8"/>
            <line x1="16" y1="13" x2="8" y2="13"/>
            <line x1="16" y1="17" x2="8" y2="17"/>
            <polyline points="10 9 9 9 8 9"/>
          </svg>
          <h3 class="modal-title">Thông tin chi tiết tài liệu</h3>
        </div>
        <button class="btn-close" @click="$emit('close')">×</button>
      </div>

      <div class="modal-body">
        <div class="meta-row">
          <span class="meta-label">Tên tệp:</span>
          <span class="meta-value bold">{{ doc.filename }}</span>
        </div>

        <div class="meta-row">
          <span class="meta-label">Document ID:</span>
          <span class="meta-value font-mono">{{ doc.document_id }}</span>
        </div>

        <div class="meta-row">
          <span class="meta-label">Trạng thái:</span>
          <span class="meta-value">
            <span v-if="doc.status === 'READY'" class="badge badge-ready">READY (Đã xử lý & index)</span>
            <span v-else-if="doc.status === 'PARSING'" class="badge badge-parsing">PARSING (Đang phân tích)</span>
            <span v-else class="badge badge-failed">FAILED (Lỗi trích xuất)</span>
          </span>
        </div>

        <div class="meta-row">
          <span class="meta-label">Số trang:</span>
          <span class="meta-value bold">{{ doc.pages || 0 }} trang</span>
        </div>

        <!-- Engine Breakdown Details -->
        <div class="engine-breakdown-section">
          <h4 class="section-heading">Phân bổ động cơ bóc tách (Engine Breakdown)</h4>
          
          <div v-if="hasBreakdown" class="breakdown-list">
            <div 
              v-for="(count, engine) in doc.engine_breakdown" 
              :key="engine"
              class="breakdown-item"
            >
              <div class="breakdown-header">
                <span class="engine-name">{{ engine }}</span>
                <span class="engine-count">{{ count }} trang ({{ calcPercent(count) }}%)</span>
              </div>
              <div class="progress-bar-bg">
                <div class="progress-bar-fill" :style="{ width: calcPercent(count) + '%' }"></div>
              </div>
            </div>
          </div>
          <p v-else class="text-empty">
            Chưa có thông tin breakdown động cơ phân tích.
          </p>
        </div>

        <div class="info-note">
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="16" x2="12" y2="12"/>
            <line x1="12" y1="8" x2="12.01" y2="8"/>
          </svg>
          <span>Tài liệu đã được bóc tách block chuẩn hóa, chunking ngữ nghĩa, tạo vector embedding 384-dim và lưu vào PostgreSQL pgvector.</span>
        </div>
      </div>

      <div class="modal-footer">
        <button class="btn btn-danger-ghost" @click="$emit('delete-doc', doc)">
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 6h18"/>
            <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/>
            <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/>
            <line x1="10" y1="11" x2="10" y2="17"/>
            <line x1="14" y1="11" x2="14" y2="17"/>
          </svg>
          Xóa tài liệu & dữ liệu embedding
        </button>
        <button class="btn btn-secondary" @click="$emit('close')">Đóng</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  doc: {
    type: Object,
    default: null
  }
})

defineEmits(['close', 'delete-doc'])

const hasBreakdown = computed(() => {
  return props.doc?.engine_breakdown && Object.keys(props.doc.engine_breakdown).length > 0
})

const totalBreakdownPages = computed(() => {
  if (!hasBreakdown.value) return props.doc?.pages || 1
  return Object.values(props.doc.engine_breakdown).reduce((a, b) => a + b, 0) || 1
})

const calcPercent = (count) => {
  return Math.round((count / totalBreakdownPages.value) * 100)
}
</script>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 20px;
}

.modal-card {
  width: 100%;
  max-width: 520px;
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
  animation: modalEnter 0.2s ease-out;
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
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-subtle);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.modal-title-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.title-icon {
  color: #818cf8;
}

.modal-title {
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--text-main);
}

.btn-close {
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: 1.5rem;
  cursor: pointer;
  line-height: 1;
  padding: 0 4px;
}

.btn-close:hover {
  color: #ffffff;
}

.modal-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 0.85rem;
}

.meta-label {
  color: var(--text-dim);
}

.meta-value {
  color: var(--text-main);
  text-align: right;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta-value.bold {
  font-weight: 600;
}

.font-mono {
  font-family: var(--font-mono);
  font-size: 0.78rem;
  color: #a5b4fc;
}

.engine-breakdown-section {
  margin-top: 10px;
  padding-top: 14px;
  border-top: 1px solid var(--border-subtle);
}

.section-heading {
  font-size: 0.8rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--text-dim);
  margin-bottom: 12px;
}

.breakdown-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.breakdown-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.breakdown-header {
  display: flex;
  justify-content: space-between;
  font-size: 0.76rem;
}

.engine-name {
  font-family: var(--font-mono);
  color: #e2e8f0;
  font-weight: 600;
}

.engine-count {
  color: var(--text-dim);
}

.progress-bar-bg {
  height: 6px;
  background: rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.progress-bar-fill {
  height: 100%;
  background: var(--accent-gradient);
  border-radius: var(--radius-full);
}

.text-empty {
  font-size: 0.8rem;
  color: var(--text-dim);
  font-style: italic;
}

.info-note {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-top: 8px;
  padding: 10px 12px;
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.2);
  border-radius: var(--radius-sm);
  font-size: 0.74rem;
  color: #c7d2fe;
  line-height: 1.5;
}

.info-note svg {
  flex-shrink: 0;
  margin-top: 2px;
  stroke: #818cf8;
}

.modal-footer {
  padding: 12px 20px;
  border-top: 1px solid var(--border-subtle);
  background: rgba(10, 15, 26, 0.4);
  display: flex;
  justify-content: flex-end;
}
</style>
