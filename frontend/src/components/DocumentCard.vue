<template>
  <div 
    class="doc-card"
    :class="{ 
      'is-selected': isSelected,
      'is-inspecting': isInspecting,
      'is-parsing': doc.status === 'PARSING',
      'is-failed': doc.status === 'FAILED'
    }"
    @click="$emit('inspect-doc', doc)"
  >
    <!-- Top Row: Checkbox + File Type Badge + Full-Width Wrapped Filename -->
    <div class="doc-top-row">
      <label class="checkbox-wrapper" title="Chọn/Bỏ chọn tài liệu này cho RAG" @click.stop>
        <input 
          type="checkbox" 
          :checked="isSelected" 
          :disabled="doc.status !== 'READY'"
          @change="$emit('toggle-select', doc.document_id)"
        />
        <span class="custom-checkbox" :class="{ 'disabled': doc.status !== 'READY' }"></span>
      </label>

      <div class="file-icon-badge" :class="fileTypeClass">
        {{ fileExtension }}
      </div>

      <h4 class="doc-name" :title="doc.filename">{{ doc.filename }}</h4>
    </div>

    <!-- Middle Row: Status, Lifecycle & Page Assignment -->
    <div class="doc-meta">
      <!-- Status Badge -->
      <span v-if="doc.status === 'READY'" class="badge badge-ready">
        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="20 6 9 17 4 12"/>
        </svg>
        Sẵn sàng ({{ doc.pages || 0 }} trang)
      </span>

      <span v-else-if="doc.status === 'PARSING'" class="badge badge-parsing">
        <span class="pulsing-indicator"></span>
        Đang phân tích...
      </span>

      <span v-else class="badge badge-failed">
        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="8" x2="12" y2="12"/>
          <line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
        Lỗi phân tích
      </span>

      <!-- Lifecycle / Time-Bound Knowledge Badges -->
      <span v-if="doc.is_active === false" class="badge badge-paused" title="Tài liệu đang tạm ngưng tra cứu RAG">
        ⏸️ Tạm ngưng
      </span>
      <span v-else-if="doc.is_expired" class="badge badge-expired" title="Tài liệu khuyến mãi/bảng giá đã quá hạn hiệu lực">
        ⌛ Đã hết hạn
      </span>
      <span v-else-if="doc.effective_to" class="badge badge-timebound" :title="'Hiệu lực đến: ' + formatShortDate(doc.effective_to)">
        📅 Đến {{ formatShortDate(doc.effective_to) }}
      </span>

      <!-- Page / Folder Assignment Badge & Quick Switcher -->
      <div v-if="pages && pages.length > 0" class="page-assign-wrapper" @click.stop>
        <select 
          class="page-assign-select" 
          :class="{ 'is-unassigned': !doc.page_id }"
          :value="doc.page_id || ''"
          title="Chuyển tài liệu sang Page / Thư mục tri thức khác"
          @change="$emit('assign-page', { documentId: doc.document_id, docId: doc.document_id, pageId: $event.target.value || null })"
        >
          <option value="">📁 Chưa phân loại</option>
          <option v-for="p in pages" :key="p.id" :value="p.id">
            📂 {{ p.name }}
          </option>
        </select>
      </div>

      <!-- Engine Breakdown pills if ready -->
      <div v-if="doc.engine_breakdown && Object.keys(doc.engine_breakdown).length > 0" class="engine-tags">
        <span 
          v-for="(count, engine) in doc.engine_breakdown" 
          :key="engine"
          class="engine-pill"
        >
          {{ engine }}: {{ count }}
        </span>
      </div>
    </div>

    <!-- Bottom Row: Aligned Action Buttons (Never overflows narrow sidebars) -->
    <div class="doc-actions-row" @click.stop>
      <button
        type="button"
        class="btn-doc-action btn-doc-inspect"
        :class="{ active: isInspecting }"
        title="Xem nội dung chi tiết, danh sách Chunks & Bảng giá ở khung lớn bên phải"
        @click="$emit('inspect-doc', doc)"
      >
        <span>📑 Xem nội dung / Chunks</span>
      </button>

      <div class="doc-actions-right">
        <button 
          type="button"
          class="btn-doc-action" 
          title="Cấu hình thời hạn hiệu lực & thông tin tài liệu"
          @click="$emit('view-detail', doc)"
        >
          ⚙️ Hiệu lực
        </button>

        <button 
          type="button"
          class="btn-doc-action btn-doc-delete" 
          title="Xóa tài liệu & dữ liệu embedding"
          @click="$emit('delete-doc', doc)"
        >
          🗑️ Xóa
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  doc: {
    type: Object,
    required: true
  },
  isSelected: {
    type: Boolean,
    default: false
  },
  isInspecting: {
    type: Boolean,
    default: false
  },
  pages: {
    type: Array,
    default: () => []
  }
})

defineEmits(['toggle-select', 'inspect-doc', 'view-detail', 'delete-doc', 'assign-page'])

const fileExtension = computed(() => {
  const parts = (props.doc.filename || '').split('.')
  if (parts.length > 1) {
    return parts.pop().toUpperCase().slice(0, 4)
  }
  return 'FILE'
})

const fileTypeClass = computed(() => {
  const ext = fileExtension.value.toLowerCase()
  if (ext === 'pdf') return 'type-pdf'
  if (['doc', 'docx'].includes(ext)) return 'type-doc'
  if (['xls', 'xlsx'].includes(ext)) return 'type-xls'
  if (['ppt', 'pptx'].includes(ext)) return 'type-ppt'
  if (['md', 'txt'].includes(ext)) return 'type-txt'
  return 'type-other'
})

const formatShortDate = (iso) => {
  if (!iso) return ''
  try {
    return new Date(iso).toLocaleDateString('vi-VN')
  } catch {
    return iso.slice(0, 10)
  }
}
</script>

<style scoped>
.doc-card {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  padding: 10px 11px;
  background: #F8FAFD;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  transition: all 0.16s cubic-bezier(0.2, 0, 0, 1);
  cursor: pointer;
  position: relative;
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
  overflow: hidden;
}

.doc-card:hover {
  background: #F0F4F9;
  border-color: #C4C7C5;
}

.doc-card.is-selected {
  border-color: #C2D7F8;
  background: #F4F8FF;
}

.doc-card.is-inspecting {
  border-color: var(--accent-primary);
  background: #E8F0FE;
  box-shadow: 0 0 0 1px rgba(11, 87, 208, 0.16);
}

.doc-card.is-parsing {
  border-color: var(--status-parsing-border);
  background: #FFFDF5;
}

.doc-card.is-failed {
  border-color: var(--status-failed-border);
  background: #FFF8F7;
}

.doc-top-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}

/* Custom Checkbox */
.checkbox-wrapper {
  position: relative;
  display: inline-flex;
  cursor: pointer;
  flex-shrink: 0;
  margin-top: 2px;
}

.checkbox-wrapper input {
  position: absolute;
  opacity: 0;
  cursor: pointer;
  height: 0;
  width: 0;
}

.custom-checkbox {
  width: 18px;
  height: 18px;
  background: #FFFFFF;
  border: 1.5px solid #8E918F;
  border-radius: 5px;
  display: inline-block;
  transition: all 0.15s ease;
}

.checkbox-wrapper:hover input ~ .custom-checkbox {
  border-color: var(--accent-primary);
}

.checkbox-wrapper input:checked ~ .custom-checkbox {
  background: var(--accent-primary);
  border-color: var(--accent-primary);
}

.checkbox-wrapper input:checked ~ .custom-checkbox::after {
  content: "";
  position: absolute;
  left: 6px;
  top: 2px;
  width: 4px;
  height: 9px;
  border: solid white;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}

.custom-checkbox.disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

/* File icon badges */
.file-icon-badge {
  font-family: var(--font-mono);
  font-size: 0.64rem;
  font-weight: 700;
  padding: 3px 6px;
  border-radius: 6px;
  letter-spacing: 0.04em;
  min-width: 36px;
  text-align: center;
  flex-shrink: 0;
  margin-top: 1px;
}

.type-pdf {
  background: #FCE8E6;
  color: #C5221F;
  border: 1px solid #FAD2CF;
}

.type-doc {
  background: #D3E3FD;
  color: #0B57D0;
  border: 1px solid #A8C7FA;
}

.type-xls {
  background: #CEEAD6;
  color: #137333;
  border: 1px solid #A8DAB5;
}

.type-ppt {
  background: #FEF7E0;
  color: #B06000;
  border: 1px solid #FDE293;
}

.type-txt {
  background: #E8DDF8;
  color: #3F1D74;
  border: 1px solid #D7C2F5;
}

.type-other {
  background: #E9EEF6;
  color: #444746;
  border: 1px solid #DDE3EA;
}

.doc-name {
  flex: 1;
  min-width: 0;
  font-size: 0.82rem;
  font-weight: 700;
  color: var(--text-main);
  line-height: 1.35;
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
  white-space: normal;
}

.doc-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  min-width: 0;
}

.engine-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.page-assign-wrapper {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  max-width: 100%;
}

.page-assign-select {
  background: #FFFFFF;
  border: 1px solid #C4C7C5;
  color: var(--text-secondary);
  font-size: 0.68rem;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 6px;
  max-width: 145px;
  min-width: 0;
  text-overflow: ellipsis;
  overflow: hidden;
  outline: none;
  cursor: pointer;
  transition: all 0.15s ease;
}

.page-assign-select:hover {
  border-color: var(--accent-primary);
  color: var(--accent-primary);
}

.page-assign-select.is-unassigned {
  background: #F0F4F9;
  border-color: #DDE3EA;
  color: var(--text-dim);
}

.page-assign-select option {
  background: #FFFFFF;
  color: var(--text-main);
}

.engine-pill {
  font-size: 0.66rem;
  font-family: var(--font-mono);
  background: #FFFFFF;
  color: var(--text-dim);
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--border-subtle);
}

.badge-paused {
  background: #F1F5F9;
  color: #475569;
  border: 1px solid #CBD5E1;
}

.badge-expired {
  background: #FEF2F2;
  color: #B91C1C;
  border: 1px solid #FECACA;
}

.badge-timebound {
  background: #EFF6FF;
  color: #1D4ED8;
  border: 1px solid #BFDBFE;
}

.doc-actions-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px;
  padding-top: 6px;
  border-top: 1px dashed #DDE3EA;
  min-width: 0;
}

.doc-actions-right {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}

.btn-doc-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid #CBD5E1;
  background: #FFFFFF;
  color: var(--text-secondary);
  font-size: 0.69rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.btn-doc-action:hover {
  color: var(--text-main);
  background: #E9EEF6;
  border-color: #94A3B8;
}

.btn-doc-inspect {
  color: var(--accent-primary);
  border-color: #A8C7FA;
  background: #FFFFFF;
}

.btn-doc-inspect.active,
.btn-doc-inspect:hover {
  background: var(--accent-primary);
  border-color: var(--accent-primary);
  color: #FFFFFF;
}

.btn-doc-delete:hover {
  color: #C5221F;
  background: #FCE8E6;
  border-color: #F6AEA9;
}
</style>
