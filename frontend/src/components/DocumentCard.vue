<template>
  <div 
    class="doc-card"
    :class="{ 
      'is-selected': isSelected,
      'is-parsing': doc.status === 'PARSING',
      'is-failed': doc.status === 'FAILED'
    }"
    @click="$emit('toggle-select', doc.document_id)"
  >
    <div class="card-left">
      <!-- Checkbox (only clickable for READY documents) -->
      <label class="checkbox-wrapper" @click.stop>
        <input 
          type="checkbox" 
          :checked="isSelected" 
          :disabled="doc.status !== 'READY'"
          @change="$emit('toggle-select', doc.document_id)"
        />
        <span class="custom-checkbox" :class="{ 'disabled': doc.status !== 'READY' }"></span>
      </label>

      <!-- File Type Icon -->
      <div class="file-icon-badge" :class="fileTypeClass">
        {{ fileExtension }}
      </div>
    </div>

    <div class="card-center">
      <div class="doc-header">
        <h4 class="doc-name" :title="doc.filename">{{ doc.filename }}</h4>
      </div>

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
    </div>

    <!-- Quick action / ID preview & Delete -->
    <div class="card-right" @click.stop>
      <button 
        class="btn-icon" 
        title="Xem chi tiết tài liệu"
        @click="$emit('view-detail', doc)"
      >
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="1"/>
          <circle cx="19" cy="12" r="1"/>
          <circle cx="5" cy="12" r="1"/>
        </svg>
      </button>

      <button 
        class="btn-icon btn-delete" 
        title="Xóa tài liệu & dữ liệu embedding"
        @click="$emit('delete-doc', doc)"
      >
        <svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 6h18"/>
          <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/>
          <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/>
          <line x1="10" y1="11" x2="10" y2="17"/>
          <line x1="14" y1="11" x2="14" y2="17"/>
        </svg>
      </button>
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
  pages: {
    type: Array,
    default: () => []
  }
})

defineEmits(['toggle-select', 'view-detail', 'delete-doc', 'assign-page'])

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
</script>

<style scoped>
.doc-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #F8FAFD;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  transition: all 0.16s cubic-bezier(0.2, 0, 0, 1);
  cursor: pointer;
  position: relative;
}

.doc-card:hover {
  background: #F0F4F9;
  border-color: #C4C7C5;
}

.doc-card.is-selected {
  border-color: #A8C7FA;
  background: #E8F0FE;
}

.doc-card.is-parsing {
  border-color: var(--status-parsing-border);
  background: #FFFDF5;
}

.doc-card.is-failed {
  border-color: var(--status-failed-border);
  background: #FFF8F7;
}

.card-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* Custom Checkbox */
.checkbox-wrapper {
  position: relative;
  display: inline-flex;
  cursor: pointer;
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
  font-size: 0.65rem;
  font-weight: 700;
  padding: 4px 6px;
  border-radius: 6px;
  letter-spacing: 0.04em;
  min-width: 38px;
  text-align: center;
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

.card-center {
  flex: 1;
  min-width: 0;
}

.doc-name {
  font-size: 0.83rem;
  font-weight: 600;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 4px;
}

.doc-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
}

.engine-tags {
  display: flex;
  gap: 4px;
}

.page-assign-wrapper {
  display: inline-flex;
  align-items: center;
}

.page-assign-select {
  background: #FFFFFF;
  border: 1px solid #C4C7C5;
  color: var(--text-secondary);
  font-size: 0.68rem;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 6px;
  max-width: 155px;
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

.card-right {
  display: flex;
  align-items: center;
}

.btn-icon {
  background: none;
  border: none;
  color: var(--text-dim);
  padding: 5px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-icon:hover {
  color: var(--text-main);
  background: #E9EEF6;
}

.btn-delete:hover {
  color: #C5221F;
  background: #FCE8E6;
}
</style>
