<template>
  <aside class="sidebar-container">
    <!-- Header / Branding -->
    <div class="sidebar-header">
      <div class="brand">
        <div class="brand-logo">
          <svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z"/>
            <path d="M6 6h10"/>
            <path d="M6 10h10"/>
            <path d="M6 14h6"/>
          </svg>
        </div>
        <div>
          <h2 class="brand-title">DocMind</h2>
          <span class="brand-tag">Grounded RAG AI</span>
        </div>
      </div>

      <!-- Live / Demo Indicator -->
      <div class="connection-badge" :class="{ 'is-live': isConnected, 'is-demo': !isConnected }" :title="connectionTooltip">
        <span class="status-dot"></span>
        <span>{{ isConnected ? 'API Live (8090)' : 'Chế độ Demo' }}</span>
        <button class="toggle-mode-btn" @click="$emit('toggle-demo')" :title="isConnected ? 'Chuyển sang Demo' : 'Thử kết nối lại API thật'">
          <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/>
            <path d="M3 3v5h5"/>
            <path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"/>
            <path d="M16 21h5v-5"/>
          </svg>
        </button>
      </div>
    </div>

    <!-- 1-Level Knowledge Pages / Folders Section -->
    <div class="pages-section">
      <div class="pages-header-row">
        <h3 class="section-title">
          📂 Phân loại Page / Ngành hàng
          <span class="count-badge">{{ pages.length }}</span>
        </h3>
        <button class="btn-add-page" @click="openCreatePageForm" title="Tạo Page / Thư mục tri thức mới">
          + Tạo Page
        </button>
      </div>

      <!-- Inline Create / Edit Page Form -->
      <div v-if="showPageForm" class="page-form-card">
        <div class="page-form-top">
          <span class="page-form-title">{{ editingPageId ? '✏️ Sửa thông tin Page' : '✨ Tạo Page tri thức mới' }}</span>
          <button class="btn-close-mini" @click="closePageForm">×</button>
        </div>
        <input 
          v-model="pageForm.name" 
          type="text" 
          class="page-input" 
          placeholder="Tên Page / Kênh (VD: Page Mỹ Phẩm Spa) *"
        />
        <div class="page-form-row">
          <input 
            v-model="pageForm.industry" 
            type="text" 
            class="page-input" 
            placeholder="Ngành nghề (VD: Làm đẹp)"
          />
          <input 
            v-model="pageForm.target_audience" 
            type="text" 
            class="page-input" 
            placeholder="Đối tượng KH (VD: Nữ 25-45)"
          />
        </div>
        <textarea 
          v-model="pageForm.description" 
          rows="2"
          class="page-input page-textarea" 
          placeholder="🎭 Page Persona & Phong cách Sale (VD: Xưng Em - Chị dịu dàng, luôn báo giá trọn gói, không chèo kéo)"
        ></textarea>
        <div class="page-form-actions">
          <button class="btn-cancel-mini" @click="closePageForm">Hủy</button>
          <button 
            class="btn-save-mini" 
            :disabled="!pageForm.name.trim()" 
            @click="submitPageForm"
          >
            {{ editingPageId ? 'Cập nhật' : 'Tạo Page' }}
          </button>
        </div>
      </div>

      <!-- 1-Level Flat Folder List -->
      <div class="folder-list">
        <!-- All Documents Option -->
        <div 
          class="folder-item" 
          :class="{ active: !activePageId }"
          @click="$emit('select-page', null)"
        >
          <div class="folder-main">
            <span class="folder-icon">🌐</span>
            <span class="folder-name">Tất cả các Page (Kho chung)</span>
          </div>
          <span class="folder-count">{{ documents.length }}</span>
        </div>

        <!-- Individual Page Folders -->
        <div 
          v-for="page in pages" 
          :key="page.id"
          class="folder-item"
          :class="{ active: activePageId === page.id }"
          @click="$emit('select-page', page.id)"
        >
          <div class="folder-content">
            <div class="folder-main">
              <span class="folder-icon">{{ activePageId === page.id ? '📂' : '📁' }}</span>
              <span class="folder-name" :title="page.name">{{ page.name }}</span>
            </div>
            <div v-if="page.industry || page.target_audience || page.description" class="folder-tags">
              <span v-if="page.industry" class="tag-industry" :title="'Ngành nghề: ' + page.industry">
                🏷️ {{ page.industry }}
              </span>
              <span v-if="page.target_audience" class="tag-audience" :title="'Khách hàng: ' + page.target_audience">
                👥 {{ page.target_audience }}
              </span>
              <span v-if="page.description" class="tag-persona" :title="'Persona: ' + page.description">
                🎭 Persona
              </span>
            </div>
          </div>

          <div class="folder-right" @click.stop>
            <span class="folder-count" :title="getPageDocCount(page.id) + ' tài liệu'">
              {{ getPageDocCount(page.id) }}
            </span>
            <button class="folder-action-btn" title="Sửa thông tin Page" @click="openEditPageForm(page)">
              ✎
            </button>
            <button class="folder-action-btn btn-del" title="Xóa Page này" @click="$emit('delete-page', page)">
              ×
            </button>
          </div>
        </div>

        <!-- Unassigned Documents Option (if any exist) -->
        <div 
          v-if="unassignedCount > 0"
          class="folder-item folder-unassigned" 
          :class="{ active: activePageId === '__unassigned__' }"
          @click="$emit('select-page', '__unassigned__')"
        >
          <div class="folder-main">
            <span class="folder-icon">🗂️</span>
            <span class="folder-name">Tài liệu chưa phân loại Page</span>
          </div>
          <span class="folder-count">{{ unassignedCount }}</span>
        </div>
      </div>
    </div>

    <!-- Upload Section -->
    <div class="upload-section">
      <div class="upload-target-banner" :class="{ 'has-page': activePageObj }">
        <span v-if="activePageObj">
          📥 Upload thẳng vào: <strong>📂 {{ activePageObj.name }}</strong>
        </span>
        <span v-else>
          📥 Upload vào: <strong>Kho tài liệu chung</strong> (Chọn Page ở trên để phân loại riêng)
        </span>
      </div>
      <DocumentUploadZone 
        :is-uploading="isUploading"
        @file-selected="$emit('upload-file', $event)"
      />
    </div>

    <!-- Filter & Action Controls -->
    <div class="documents-header">
      <div class="header-title-row">
        <h3 class="section-title">
          {{ activePageObj ? `Tài liệu: ${activePageObj.name}` : 'Danh sách tài liệu' }}
          <span class="count-badge">{{ filteredDocuments.length }}</span>
        </h3>
        <button class="btn-refresh" title="Làm mới danh sách" @click="$emit('refresh-documents')">
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/>
            <path d="M3 3v5h5"/>
            <path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"/>
            <path d="M16 21h5v-5"/>
          </svg>
        </button>
      </div>

      <!-- Quick Status Filter Pills -->
      <div class="filter-pills">
        <button 
          class="pill-btn" 
          :class="{ active: currentFilter === 'all' }"
          @click="currentFilter = 'all'"
        >
          Tất cả ({{ pageScopedDocuments.length }})
        </button>
        <button 
          class="pill-btn" 
          :class="{ active: currentFilter === 'READY' }"
          @click="currentFilter = 'READY'"
        >
          Sẵn sàng ({{ readyCount }})
        </button>
        <button 
          v-if="parsingCount > 0"
          class="pill-btn pill-amber" 
          :class="{ active: currentFilter === 'PARSING' }"
          @click="currentFilter = 'PARSING'"
        >
          Đang xử lý ({{ parsingCount }})
        </button>
        <button 
          v-if="failedCount > 0"
          class="pill-btn pill-rose" 
          :class="{ active: currentFilter === 'FAILED' }"
          @click="currentFilter = 'FAILED'"
        >
          Lỗi ({{ failedCount }})
        </button>
      </div>

      <!-- Selection Helper -->
      <div class="selection-bar">
        <button class="btn-text" @click="$emit('toggle-select-all')">
          {{ isAllSelected ? 'Bỏ chọn tất cả trong Page' : 'Chọn tất cả tài liệu sẵn sàng' }}
        </button>
        <span class="selection-count">
          Đã chọn: <strong>{{ selectedDocIds.length }}</strong>
        </span>
      </div>
    </div>

    <!-- Document List -->
    <div class="documents-list">
      <div v-if="filteredDocuments.length === 0" class="empty-docs">
        <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" class="empty-icon">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
          <polyline points="14 2 14 8 20 8"/>
          <line x1="12" y1="18" x2="12" y2="12"/>
          <line x1="9" y1="15" x2="15" y2="15"/>
        </svg>
        <p>{{ activePageObj ? `Page "${activePageObj.name}" chưa có tài liệu nào.` : 'Chưa có tài liệu nào phù hợp.' }}</p>
        <span class="empty-hint">Hãy kéo thả tệp DOCX, XLSX, MD, PDF vào ô phía trên để đào tạo cho Page này.</span>
      </div>

      <DocumentCard 
        v-for="doc in filteredDocuments"
        :key="doc.document_id"
        :doc="doc"
        :pages="pages"
        :is-selected="selectedDocIds.includes(doc.document_id)"
        @toggle-select="$emit('toggle-select', $event)"
        @view-detail="$emit('view-detail', $event)"
        @delete-doc="$emit('delete-doc', $event)"
        @assign-page="$emit('assign-page', $event)"
      />
    </div>

    <!-- Playbook Shortcut Bar -->
    <div class="sidebar-playbook-bar">
      <button class="btn-playbook-sidebar" @click="$emit('open-playbook')" title="Mở Bộ nhớ Kinh nghiệm AI">
        <div class="playbook-sidebar-left">
          <div class="playbook-icon-badge">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z"/>
              <path d="M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z"/>
              <path d="M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4"/>
            </svg>
          </div>
          <div class="playbook-text-group">
            <span class="playbook-main-label">Bộ nhớ Kinh nghiệm AI</span>
            <span class="playbook-sub-label">
              {{ activePageObj ? `Playbook riêng: ${activePageObj.name}` : 'AI Playbook & Meta-Rules' }}
            </span>
          </div>
        </div>
        <span class="playbook-arrow">→</span>
      </button>
    </div>

    <!-- Footer Stats / Notes -->
    <div class="sidebar-footer">
      <div class="engine-info">
        <span class="dot-spring"></span>
        <span>Spring Boot 4 + FastPARSER + pgvector</span>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed } from 'vue'
import DocumentUploadZone from './DocumentUploadZone.vue'
import DocumentCard from './DocumentCard.vue'

const props = defineProps({
  documents: {
    type: Array,
    default: () => []
  },
  pages: {
    type: Array,
    default: () => []
  },
  activePageId: {
    type: String,
    default: null
  },
  selectedDocIds: {
    type: Array,
    default: () => []
  },
  isUploading: {
    type: Boolean,
    default: false
  },
  isConnected: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits([
  'upload-file',
  'toggle-select',
  'toggle-select-all',
  'refresh-documents',
  'view-detail',
  'delete-doc',
  'toggle-demo',
  'open-playbook',
  'select-page',
  'create-page',
  'update-page',
  'delete-page',
  'assign-page'
])

const currentFilter = ref('all')

const showPageForm = ref(false)
const editingPageId = ref(null)
const pageForm = ref({
  name: '',
  industry: '',
  target_audience: '',
  description: ''
})

const activePageObj = computed(() => {
  if (!props.activePageId || props.activePageId === '__unassigned__') return null
  return props.pages.find(p => p.id === props.activePageId) || null
})

const unassignedCount = computed(() => {
  return props.documents.filter(d => !d.page_id).length
})

const getPageDocCount = (pageId) => {
  return props.documents.filter(d => d.page_id === pageId).length
}

const pageScopedDocuments = computed(() => {
  if (!props.activePageId) return props.documents
  if (props.activePageId === '__unassigned__') {
    return props.documents.filter(d => !d.page_id)
  }
  return props.documents.filter(d => d.page_id === props.activePageId)
})

const readyCount = computed(() => pageScopedDocuments.value.filter(d => d.status === 'READY').length)
const parsingCount = computed(() => pageScopedDocuments.value.filter(d => d.status === 'PARSING').length)
const failedCount = computed(() => pageScopedDocuments.value.filter(d => d.status === 'FAILED').length)

const filteredDocuments = computed(() => {
  if (currentFilter.value === 'all') return pageScopedDocuments.value
  return pageScopedDocuments.value.filter(d => d.status === currentFilter.value)
})

const readyDocIds = computed(() => 
  pageScopedDocuments.value.filter(d => d.status === 'READY').map(d => d.document_id)
)

const isAllSelected = computed(() => {
  if (readyDocIds.value.length === 0) return false
  return readyDocIds.value.every(id => props.selectedDocIds.includes(id))
})

const connectionTooltip = computed(() => {
  return props.isConnected 
    ? 'Đã kết nối backend Spring Boot localhost:8090' 
    : 'Chế độ Demo (Không cần bật Docker/backend)'
})

const openCreatePageForm = () => {
  editingPageId.value = null
  pageForm.value = { name: '', industry: '', target_audience: '', description: '' }
  showPageForm.value = true
}

const openEditPageForm = (page) => {
  editingPageId.value = page.id
  pageForm.value = {
    name: page.name || '',
    industry: page.industry || '',
    target_audience: page.target_audience || '',
    description: page.description || ''
  }
  showPageForm.value = true
}

const closePageForm = () => {
  showPageForm.value = false
  editingPageId.value = null
}

const submitPageForm = () => {
  if (!pageForm.value.name.trim()) return
  const payload = {
    name: pageForm.value.name.trim(),
    industry: pageForm.value.industry.trim(),
    target_audience: pageForm.value.target_audience.trim(),
    description: pageForm.value.description.trim()
  }
  if (editingPageId.value) {
    emit('update-page', { id: editingPageId.value, data: payload })
  } else {
    emit('create-page', payload)
  }
  closePageForm()
}
</script>

<style scoped>
.sidebar-container {
  width: 400px;
  min-width: 360px;
  max-width: 440px;
  height: 100%;
  background: var(--bg-secondary);
  border-right: 1px solid var(--border-subtle);
  display: flex;
  flex-direction: column;
  user-select: none;
}

.sidebar-header {
  padding: 14px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border-subtle);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-logo {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: var(--accent-gradient);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  box-shadow: 0 4px 14px rgba(99, 102, 241, 0.4);
}

.brand-title {
  font-size: 1.1rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  background: linear-gradient(to right, #ffffff, #c7d2fe);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  line-height: 1.2;
}

.brand-tag {
  font-size: 0.68rem;
  color: var(--text-dim);
  font-weight: 500;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.connection-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: var(--radius-full);
  font-size: 0.7rem;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border-subtle);
}

.connection-badge.is-live {
  background: var(--status-ready-bg);
  border-color: var(--status-ready-border);
  color: var(--status-ready-text);
}

.connection-badge.is-demo {
  background: var(--status-parsing-bg);
  border-color: var(--status-parsing-border);
  color: var(--status-parsing-text);
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.toggle-mode-btn {
  background: none;
  border: none;
  color: inherit;
  cursor: pointer;
  padding: 2px;
  display: flex;
  align-items: center;
  opacity: 0.7;
  transition: opacity 0.15s ease;
}

.toggle-mode-btn:hover {
  opacity: 1;
}

/* Pages / Folders Section */
.pages-section {
  padding: 12px 18px 10px;
  border-bottom: 1px solid var(--border-subtle);
  background: rgba(15, 23, 42, 0.35);
}

.pages-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.btn-add-page {
  background: rgba(99, 102, 241, 0.16);
  border: 1px solid rgba(99, 102, 241, 0.4);
  color: #a5b4fc;
  font-size: 0.72rem;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-add-page:hover {
  background: rgba(99, 102, 241, 0.28);
  color: #ffffff;
}

.page-form-card {
  background: var(--bg-card);
  border: 1px solid var(--border-active);
  border-radius: var(--radius-md);
  padding: 10px;
  margin-bottom: 10px;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.page-form-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-form-title {
  font-size: 0.76rem;
  font-weight: 600;
  color: #a5b4fc;
}

.btn-close-mini {
  background: none;
  border: none;
  color: var(--text-dim);
  cursor: pointer;
  font-size: 1rem;
  line-height: 1;
}

.page-form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}

.page-input {
  width: 100%;
  background: var(--bg-input);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  color: var(--text-main);
  font-size: 0.76rem;
  outline: none;
}

.page-input:focus {
  border-color: var(--accent-primary);
}

.page-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 2px;
}

.btn-cancel-mini {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid var(--border-subtle);
  color: var(--text-muted);
  font-size: 0.72rem;
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.btn-save-mini {
  background: var(--accent-primary);
  border: none;
  color: #ffffff;
  font-size: 0.72rem;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.btn-save-mini:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.folder-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 175px;
  overflow-y: auto;
  padding-right: 2px;
}

.folder-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.025);
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.15s ease;
  gap: 8px;
}

.folder-item:hover {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.1);
}

.folder-item.active {
  background: rgba(99, 102, 241, 0.14);
  border-color: var(--border-active);
  box-shadow: inset 2px 0 0 var(--accent-primary);
}

.folder-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.folder-main {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.folder-icon {
  font-size: 0.85rem;
  line-height: 1;
}

.folder-name {
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.folder-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding-left: 20px;
}

.tag-industry,
.tag-audience,
.tag-persona {
  font-size: 0.65rem;
  padding: 1px 6px;
  border-radius: 4px;
  max-width: 145px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-industry {
  background: rgba(16, 185, 129, 0.12);
  color: #6ee7b7;
  border: 1px solid rgba(16, 185, 129, 0.25);
}

.tag-audience {
  background: rgba(245, 158, 11, 0.12);
  color: #fcd34d;
  border: 1px solid rgba(245, 158, 11, 0.25);
}

.tag-persona {
  background: rgba(168, 85, 247, 0.14);
  color: #d8b4fe;
  border: 1px solid rgba(168, 85, 247, 0.3);
}

.page-textarea {
  resize: vertical;
  min-height: 44px;
  font-family: inherit;
  line-height: 1.35;
}

.folder-right {
  display: flex;
  align-items: center;
  gap: 3px;
}

.folder-count {
  font-size: 0.68rem;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.08);
  color: var(--text-muted);
  padding: 1px 6px;
  border-radius: var(--radius-full);
}

.folder-action-btn {
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 0.78rem;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 3px;
  opacity: 0.65;
  transition: all 0.15s ease;
}

.folder-action-btn:hover {
  opacity: 1;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.1);
}

.folder-action-btn.btn-del:hover {
  color: #fb7185;
  background: rgba(244, 63, 94, 0.15);
}

.upload-section {
  padding: 10px 18px 10px;
}

.upload-target-banner {
  font-size: 0.72rem;
  color: var(--text-muted);
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  padding: 5px 10px;
  margin-bottom: 7px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.upload-target-banner.has-page {
  background: rgba(99, 102, 241, 0.1);
  border-color: rgba(99, 102, 241, 0.3);
  color: #c7d2fe;
}

.documents-header {
  padding: 0 18px 8px;
}

.header-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.section-title {
  font-size: 0.78rem;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.count-badge {
  background: rgba(255, 255, 255, 0.08);
  color: var(--text-main);
  padding: 1px 7px;
  border-radius: var(--radius-full);
  font-size: 0.68rem;
}

.btn-refresh {
  background: none;
  border: none;
  color: var(--text-dim);
  cursor: pointer;
  padding: 4px;
  border-radius: var(--radius-sm);
  transition: all 0.15s ease;
  display: flex;
  align-items: center;
}

.btn-refresh:hover {
  color: var(--text-main);
  background: rgba(255, 255, 255, 0.08);
  transform: rotate(45deg);
}

.filter-pills {
  display: flex;
  gap: 5px;
  margin-bottom: 6px;
  overflow-x: auto;
  padding-bottom: 2px;
}

.pill-btn {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border-subtle);
  color: var(--text-dim);
  font-size: 0.7rem;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.pill-btn:hover {
  color: var(--text-main);
  background: rgba(255, 255, 255, 0.08);
}

.pill-btn.active {
  background: rgba(99, 102, 241, 0.15);
  border-color: var(--border-active);
  color: #a5b4fc;
}

.pill-btn.pill-amber.active {
  background: var(--status-parsing-bg);
  border-color: var(--status-parsing-border);
  color: var(--status-parsing-text);
}

.pill-btn.pill-rose.active {
  background: var(--status-failed-bg);
  border-color: var(--status-failed-border);
  color: var(--status-failed-text);
}

.selection-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 0.72rem;
  color: var(--text-dim);
  padding-top: 4px;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}

.btn-text {
  background: none;
  border: none;
  color: #818cf8;
  cursor: pointer;
  font-size: 0.72rem;
  padding: 0;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.btn-text:hover {
  color: #a5b4fc;
}

.documents-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 18px 14px;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.empty-docs {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 28px 10px;
  color: var(--text-dim);
}

.empty-icon {
  stroke: var(--text-dim);
  margin-bottom: 10px;
  opacity: 0.5;
}

.empty-docs p {
  font-size: 0.82rem;
  color: var(--text-muted);
  margin-bottom: 4px;
}

.empty-hint {
  font-size: 0.72rem;
  color: var(--text-dim);
}

/* Playbook Shortcut */
.sidebar-playbook-bar {
  padding: 8px 16px;
  border-top: 1px solid var(--border-subtle);
  background: rgba(168, 85, 247, 0.04);
}

.btn-playbook-sidebar {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(168, 85, 247, 0.1);
  border: 1px solid rgba(168, 85, 247, 0.25);
  border-radius: var(--radius-md);
  padding: 7px 12px;
  cursor: pointer;
  transition: all 0.2s ease;
  color: var(--text-main);
  text-align: left;
}

.btn-playbook-sidebar:hover {
  background: rgba(168, 85, 247, 0.18);
  border-color: rgba(168, 85, 247, 0.45);
  transform: translateY(-1px);
}

.playbook-sidebar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.playbook-icon-badge {
  width: 28px;
  height: 28px;
  min-width: 28px;
  border-radius: var(--radius-sm);
  background: rgba(168, 85, 247, 0.2);
  color: #c084fc;
  display: flex;
  align-items: center;
  justify-content: center;
}

.playbook-text-group {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.playbook-main-label {
  font-size: 0.78rem;
  font-weight: 600;
  color: #f1f5f9;
  line-height: 1.2;
}

.playbook-sub-label {
  font-size: 0.67rem;
  color: #a855f7;
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.playbook-arrow {
  color: #a855f7;
  font-size: 0.9rem;
  font-weight: 700;
  transition: transform 0.2s ease;
}

.btn-playbook-sidebar:hover .playbook-arrow {
  transform: translateX(2px);
  color: #ffffff;
}

.sidebar-footer {
  padding: 10px 18px;
  border-top: 1px solid var(--border-subtle);
  background: rgba(10, 15, 26, 0.4);
}

.engine-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.68rem;
  color: var(--text-dim);
  font-family: var(--font-mono);
}

.dot-spring {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #6366f1;
}
</style>
