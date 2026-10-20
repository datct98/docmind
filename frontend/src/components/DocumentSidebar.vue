<template>
  <aside class="sidebar-container">
    <!-- Sources Panel Header (NotebookLM Left Bento Header) -->
    <div class="sidebar-header">
      <div class="sources-title-group">
        <div class="sources-icon-box">
          <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
            <polyline points="14 2 14 8 20 8"/>
            <line x1="16" y1="13" x2="8" y2="13"/>
            <line x1="16" y1="17" x2="8" y2="17"/>
          </svg>
        </div>
        <div>
          <h2 class="sources-panel-title">Nguồn tài liệu</h2>
          <span class="sources-panel-sub">
            {{ activePageObj ? activePageObj.name : 'Kho tài liệu chung' }}
          </span>
        </div>
      </div>

      <button
        class="btn-TogglePages"
        :class="{ active: showPagesList }"
        title="Đổi nhanh Fanpage hoặc thêm Page mới"
        @click="showPagesList = !showPagesList"
      >
        <span>📂 Đổi Page ({{ pages.length }})</span>
        <span class="chevron-mini">{{ showPagesList ? '▲' : '▼' }}</span>
      </button>
    </div>

    <!-- Collapsible 1-Level Knowledge Pages / Folders Switcher -->
    <div v-if="showPagesList" class="pages-section">
      <div class="pages-header-row">
        <h3 class="section-title">
          📂 Chuyển nhanh Fanpage
          <span class="count-badge">{{ pages.length }}</span>
        </h3>
        <div class="pages-header-actions">
          <button class="btn-hub-link" @click="$emit('back-to-hub')" title="Mở màn hình lưới tất cả Fanpage">
            Xem dạng lưới ⊞
          </button>
          <button class="btn-add-page" @click="openCreatePageForm" title="Tạo Page / Thư mục tri thức mới">
            + Tạo Page
          </button>
        </div>
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
          placeholder="🎭 Page Persona & Phong cách Sale (VD: Xưng Em - Chị dịu dàng, luôn báo giá trọn gói)"
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

    <!-- Active Page Persona Banner (When a Page is active) -->
    <div v-if="activePageObj && !showPagesList" class="active-page-summary-card">
      <div class="ap-top-row">
        <span class="ap-badge-industry">🏷️ {{ activePageObj.industry || 'Đa ngành' }}</span>
        <span v-if="activePageObj.target_audience" class="ap-badge-audience">
          👥 {{ activePageObj.target_audience }}
        </span>
      </div>
      <p v-if="activePageObj.description" class="ap-persona-text">
        🎭 <strong>Persona:</strong> {{ activePageObj.description }}
      </p>
    </div>

    <!-- Upload Section -->
    <div class="upload-section">
      <div class="upload-target-banner" :class="{ 'has-page': activePageObj }">
        <span v-if="activePageObj">
          📥 Nạp nguồn trực tiếp vào: <strong>📂 {{ activePageObj.name }}</strong>
        </span>
        <span v-else>
          📥 Nạp vào: <strong>Kho tài liệu chung</strong>
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
          {{ activePageObj ? `Tài liệu của Page` : 'Danh sách tài liệu' }}
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
          {{ isAllSelected ? 'Bỏ chọn tất cả trong Page' : 'Chọn tất cả nguồn sẵn sàng' }}
        </button>
        <span class="selection-count">
          Đang dùng: <strong>{{ selectedDocIds.length }}</strong> nguồn
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
        <span class="empty-hint">Kéo thả tệp DOCX, XLSX, MD, PDF vào khung trên hoặc bấm "⚡ Nạp thử file mẫu ngay".</span>
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
  'assign-page',
  'back-to-hub'
])

const currentFilter = ref('all')
const showPagesList = ref(false)

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
/* NotebookLM Floating Left Sources Card */
.sidebar-container {
  width: 390px;
  min-width: 350px;
  max-width: 420px;
  height: 100%;
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-bento);
  display: flex;
  flex-direction: column;
  user-select: none;
  overflow: hidden;
}

.sidebar-header {
  padding: 16px 18px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #EEF2F6;
  gap: 10px;
}

.sources-title-group {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.sources-icon-box {
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

.sources-panel-title {
  font-size: 0.96rem;
  font-weight: 700;
  color: var(--text-main);
  line-height: 1.2;
}

.sources-panel-sub {
  font-size: 0.73rem;
  color: var(--text-dim);
  font-weight: 600;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 170px;
}

.btn-TogglePages {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: var(--bg-canvas);
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-size: 0.73rem;
  font-weight: 600;
  padding: 5px 10px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.btn-TogglePages:hover,
.btn-TogglePages.active {
  background: var(--accent-pill-bg);
  border-color: #A8C7FA;
  color: var(--accent-pill-text);
}

.chevron-mini {
  font-size: 0.6rem;
}

/* Active Page Summary Card */
.active-page-summary-card {
  margin: 10px 16px 0;
  padding: 10px 12px;
  background: #F8FAFD;
  border: 1px solid #E1E8F0;
  border-radius: var(--radius-md);
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.ap-top-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.ap-badge-industry {
  font-size: 0.68rem;
  font-weight: 700;
  background: #CEEAD6;
  color: #0D652D;
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

.ap-badge-audience {
  font-size: 0.68rem;
  font-weight: 600;
  background: #FEF7E0;
  color: #B06000;
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

.ap-persona-text {
  font-size: 0.74rem;
  color: var(--text-secondary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* Pages / Folders Section */
.pages-section {
  padding: 12px 16px 10px;
  border-bottom: 1px solid var(--border-subtle);
  background: #F8FAFD;
}

.pages-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.pages-header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-hub-link {
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-size: 0.7rem;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.btn-hub-link:hover {
  background: #E9EEF6;
  color: var(--text-main);
}

.btn-add-page {
  background: var(--accent-pill-bg);
  border: 1px solid #A8C7FA;
  color: var(--accent-pill-text);
  font-size: 0.72rem;
  font-weight: 700;
  padding: 3px 9px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-add-page:hover {
  background: var(--accent-primary);
  color: #ffffff;
}

.page-form-card {
  background: #FFFFFF;
  border: 1px solid #A8C7FA;
  border-radius: var(--radius-md);
  padding: 10px;
  margin-bottom: 10px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  box-shadow: var(--shadow-sm);
}

.page-form-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-form-title {
  font-size: 0.76rem;
  font-weight: 700;
  color: var(--accent-primary);
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
  background: #F8FAFD;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  color: var(--text-main);
  font-size: 0.76rem;
  outline: none;
}

.page-input:focus {
  background: #FFFFFF;
  border-color: var(--accent-primary);
}

.page-textarea {
  resize: vertical;
  min-height: 44px;
  font-family: inherit;
  line-height: 1.35;
}

.page-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 2px;
}

.btn-cancel-mini {
  background: #F0F4F9;
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-size: 0.72rem;
  font-weight: 600;
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
  max-height: 180px;
  overflow-y: auto;
  padding-right: 2px;
}

.folder-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 10px;
  border-radius: var(--radius-sm);
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  cursor: pointer;
  transition: all 0.15s ease;
  gap: 8px;
}

.folder-item:hover {
  background: #F0F4F9;
  border-color: #C4C7C5;
}

.folder-item.active {
  background: #E8F0FE;
  border-color: #A8C7FA;
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
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  max-width: 145px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-industry {
  background: #CEEAD6;
  color: #0D652D;
}

.tag-audience {
  background: #FEF7E0;
  color: #B06000;
}

.tag-persona {
  background: #E8DDF8;
  color: #3F1D74;
}

.folder-right {
  display: flex;
  align-items: center;
  gap: 3px;
}

.folder-count {
  font-size: 0.68rem;
  font-weight: 700;
  background: #F0F4F9;
  color: var(--text-secondary);
  padding: 1px 7px;
  border-radius: var(--radius-full);
}

.folder-action-btn {
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 0.78rem;
  cursor: pointer;
  padding: 2px 5px;
  border-radius: 4px;
  transition: all 0.15s ease;
}

.folder-action-btn:hover {
  color: var(--text-main);
  background: #E9EEF6;
}

.folder-action-btn.btn-del:hover {
  color: #C5221F;
  background: #FCE8E6;
}

/* Upload Section */
.upload-section {
  padding: 12px 16px 10px;
}

.upload-target-banner {
  font-size: 0.72rem;
  color: var(--text-secondary);
  background: #F8FAFD;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  padding: 5px 10px;
  margin-bottom: 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.upload-target-banner.has-page {
  background: #E8F0FE;
  border-color: #D3E3FD;
  color: #041E49;
}

.documents-header {
  padding: 0 16px 8px;
}

.header-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.section-title {
  font-size: 0.75rem;
  font-weight: 700;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  display: flex;
  align-items: center;
  gap: 6px;
}

.count-badge {
  background: #E8F0FE;
  color: var(--accent-primary);
  padding: 1px 7px;
  border-radius: var(--radius-full);
  font-size: 0.68rem;
  font-weight: 700;
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
  color: var(--accent-primary);
  background: #F0F4F9;
  transform: rotate(45deg);
}

.filter-pills {
  display: flex;
  gap: 5px;
  margin-bottom: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
}

.pill-btn {
  background: #F8FAFD;
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-size: 0.71rem;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.pill-btn:hover {
  color: var(--text-main);
  background: #E9EEF6;
}

.pill-btn.active {
  background: var(--accent-pill-bg);
  border-color: #A8C7FA;
  color: var(--accent-pill-text);
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
  font-size: 0.73rem;
  color: var(--text-dim);
  padding-top: 6px;
  border-top: 1px solid #EEF2F6;
}

.btn-text {
  background: none;
  border: none;
  color: var(--accent-primary);
  font-weight: 600;
  cursor: pointer;
  font-size: 0.73rem;
  padding: 0;
}

.btn-text:hover {
  text-decoration: underline;
}

.selection-count strong {
  color: var(--text-main);
}

.documents-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 16px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.empty-docs {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 28px 12px;
  background: #F8FAFD;
  border: 1px dashed var(--border-subtle);
  border-radius: var(--radius-md);
}

.empty-icon {
  stroke: var(--text-dim);
  margin-bottom: 10px;
  opacity: 0.6;
}

.empty-docs p {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.empty-hint {
  font-size: 0.73rem;
  color: var(--text-dim);
  line-height: 1.45;
}

/* Playbook Shortcut */
.sidebar-playbook-bar {
  padding: 10px 16px 12px;
  border-top: 1px solid #EEF2F6;
  background: #F8FAFD;
}

.btn-playbook-sidebar {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #FFFFFF;
  border: 1px solid #D3E3FD;
  border-radius: var(--radius-md);
  padding: 8px 12px;
  cursor: pointer;
  transition: all 0.18s ease;
  color: var(--text-main);
  text-align: left;
  box-shadow: var(--shadow-sm);
}

.btn-playbook-sidebar:hover {
  background: #E8F0FE;
  border-color: #A8C7FA;
  transform: translateY(-1px);
}

.playbook-sidebar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.playbook-icon-badge {
  width: 30px;
  height: 30px;
  min-width: 30px;
  border-radius: var(--radius-sm);
  background: var(--accent-pill-bg);
  color: var(--accent-primary);
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
  font-weight: 700;
  color: var(--text-main);
  line-height: 1.2;
}

.playbook-sub-label {
  font-size: 0.68rem;
  color: var(--accent-primary);
  font-weight: 500;
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.playbook-arrow {
  color: var(--accent-primary);
  font-size: 0.9rem;
  font-weight: 700;
  transition: transform 0.2s ease;
}

.btn-playbook-sidebar:hover .playbook-arrow {
  transform: translateX(2px);
}
</style>
