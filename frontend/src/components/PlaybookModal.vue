<template>
  <div class="modal-backdrop" @click.self="handleClose">
    <div class="modal-card playbook-card">
      <!-- Modal Header -->
      <div class="modal-header">
        <div class="modal-title-group">
          <div class="icon-brain-box">
            <svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z"/>
              <path d="M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z"/>
              <path d="M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4"/>
              <path d="M17.599 6.5a3 3 0 0 0 .399-1.375"/>
              <path d="M6.001 5.125A3 3 0 0 0 6.4 6.5"/>
              <path d="M3.477 10.896a4 4 0 0 1 .585-.396"/>
              <path d="M19.938 10.5a4 4 0 0 1 .585.396"/>
              <path d="M6 18a4 4 0 0 1-1.967-.516"/>
              <path d="M19.967 17.484A4 4 0 0 1 18 18"/>
            </svg>
          </div>
          <div>
            <div class="title-with-badge">
              <h3 class="modal-title">Bộ nhớ Kinh nghiệm AI (AI Playbook)</h3>
              <span class="badge badge-accent">{{ rules.length }} quy tắc</span>
            </div>
            <p class="modal-subtitle">Kho quy tắc suy luận meta-learning tự động đúc kết từ phản hồi & hiệu chỉnh hội thoại</p>
          </div>
        </div>
        <button class="btn-close" @click="handleClose">×</button>
      </div>

      <!-- Action & Filter Bar -->
      <div class="toolbar-section">
        <!-- Search & Add Button Row -->
        <div class="search-action-row">
          <div class="search-input-wrapper">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="search-icon">
              <circle cx="11" cy="11" r="8"/>
              <line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <input 
              v-model="searchQuery" 
              type="text" 
              class="search-input" 
              placeholder="Tìm kiếm theo quy tắc, nguyên nhân lỗi, từ khóa..."
              @input="handleSearch"
            />
            <button v-if="searchQuery" class="btn-clear-search" @click="searchQuery = ''; handleSearch()">×</button>
          </div>

          <button class="btn btn-primary btn-sm" @click="openCreateForm">
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="12" y1="5" x2="12" y2="19"/>
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            <span>Thêm quy tắc mới</span>
          </button>
        </div>

        <!-- Page / Channel Filter Row -->
        <div v-if="pages && pages.length > 0" class="filter-chips-row page-filter-row">
          <div class="filter-group">
            <span class="filter-group-label">Phạm vi Page / Kênh:</span>
            <button 
              class="pill-filter" 
              :class="{ active: selectedPageFilter === 'ALL' }" 
              @click="selectedPageFilter = 'ALL'"
            >
              Tất cả ({{ rules.length }})
            </button>
            <button 
              class="pill-filter" 
              :class="{ active: selectedPageFilter === 'GLOBAL' }" 
              @click="selectedPageFilter = 'GLOBAL'"
            >
              🌐 Toàn hệ thống ({{ globalRulesCount }})
            </button>
            <button 
              v-for="p in pages" 
              :key="p.id"
              class="pill-filter" 
              :class="{ active: selectedPageFilter === p.id }" 
              @click="selectedPageFilter = p.id"
            >
              📂 {{ p.name }} ({{ getPageRulesCount(p.id) }})
            </button>
          </div>
        </div>

        <!-- Filter Chips Row -->
        <div class="filter-chips-row">
          <!-- Category Filters -->
          <div class="filter-group">
            <span class="filter-group-label">Danh mục:</span>
            <button 
              class="pill-filter" 
              :class="{ active: selectedCategory === '' }" 
              @click="setCategory('')"
            >
              Tất cả ({{ categoryCounts.all }})
            </button>
            <button 
              class="pill-filter" 
              :class="{ active: selectedCategory === 'PRICING' }" 
              @click="setCategory('PRICING')"
            >
              Bảng giá ({{ categoryCounts.pricing }})
            </button>
            <button 
              class="pill-filter" 
              :class="{ active: selectedCategory === 'DISCOUNT' }" 
              @click="setCategory('DISCOUNT')"
            >
              Chiết khấu ({{ categoryCounts.discount }})
            </button>
            <button 
              class="pill-filter" 
              :class="{ active: selectedCategory === 'POLICY' }" 
              @click="setCategory('POLICY')"
            >
              Chính sách ({{ categoryCounts.policy }})
            </button>
            <button 
              class="pill-filter" 
              :class="{ active: selectedCategory === 'GENERAL' }" 
              @click="setCategory('GENERAL')"
            >
              Chung ({{ categoryCounts.general }})
            </button>
          </div>

          <!-- Status Filters -->
          <div class="filter-group">
            <span class="filter-group-label">Trạng thái:</span>
            <button 
              class="pill-filter" 
              :class="{ active: statusFilter === 'all' }" 
              @click="setStatusFilter('all')"
            >
              Tất cả
            </button>
            <button 
              class="pill-filter pill-emerald" 
              :class="{ active: statusFilter === 'active' }" 
              @click="setStatusFilter('active')"
            >
              Đang bật ({{ activeCount }})
            </button>
            <button 
              class="pill-filter pill-dim" 
              :class="{ active: statusFilter === 'inactive' }" 
              @click="setStatusFilter('inactive')"
            >
              Tắt ({{ inactiveCount }})
            </button>
          </div>
        </div>
      </div>

      <!-- Create / Edit Form Overlay -->
      <div v-if="editingRule || isCreating" class="rule-form-container">
        <div class="form-header">
          <h4 class="form-title">
            {{ isCreating ? '✨ Thêm quy tắc kinh nghiệm mới' : '✏️ Chỉnh sửa quy tắc suy luận' }}
          </h4>
          <button class="btn-text-sm" @click="cancelEdit">Hủy</button>
        </div>

        <div class="form-grid">
          <div class="form-field full-width">
            <label class="field-label required">Quy tắc tổng quát (Generalized Rule / Meta-Rule):</label>
            <textarea 
              v-model="formRule.generalized_rule" 
              class="form-textarea" 
              rows="3"
              placeholder="Quy tắc chuẩn hóa suy luận không phụ thuộc vào số liệu hay giá tạm thời..."
            ></textarea>
          </div>

          <div class="form-field full-width">
            <label class="field-label">Nguyên nhân lỗi trước đây (Root Cause):</label>
            <input 
              v-model="formRule.root_cause" 
              type="text" 
              class="form-input" 
              placeholder="Ví dụ: AI áp dụng sai mức chiết khấu do nhầm lẫn giá Gross và Net..."
            />
          </div>

          <div class="form-field">
            <label class="field-label">Điều kiện kích hoạt (Trigger Condition - Tùy chọn):</label>
            <input 
              v-model="formRule.trigger_condition" 
              type="text" 
              class="form-input" 
              placeholder="Để trống hệ thống sẽ tự sinh từ nội dung quy tắc..."
            />
          </div>

          <div class="form-field">
            <label class="field-label">Phân loại (Category):</label>
            <select v-model="formRule.category" class="form-select">
              <option value="POLICY">POLICY (Quy định & Kịch bản CSKH)</option>
              <option value="PRICING">PRICING (Bảng giá)</option>
              <option value="PRICING_CALCULATION">PRICING_CALCULATION (Tính giá & Combo)</option>
              <option value="DISCOUNT">DISCOUNT (Chiết khấu)</option>
              <option value="TABLE_LOOKUP">TABLE_LOOKUP (Tra cứu bảng)</option>
              <option value="DATA_INTERPRETATION">DATA_INTERPRETATION (Diễn giải dữ liệu)</option>
              <option value="GENERAL">GENERAL (Chung)</option>
            </select>
          </div>

          <div class="form-field">
            <label class="field-label">Áp dụng cho Page / Kênh:</label>
            <select v-model="formRule.page_id" class="form-select">
              <option value="">🌐 Toàn hệ thống (Mọi Page)</option>
              <option v-for="p in pages" :key="p.id" :value="p.id">
                📂 Theo Trang: {{ p.name }} ({{ p.industry }})
              </option>
            </select>
          </div>

          <div class="form-field">
            <label class="field-label">Từ khóa mở rộng truy vấn (cách nhau bằng dấu phẩy):</label>
            <input 
              v-model="formKeywordsText" 
              type="text" 
              class="form-input" 
              placeholder="số điện thoại, liên hệ tư vấn, bảng giá..."
            />
          </div>
        </div>

        <p v-if="formError" style="color: #f87171; font-size: 0.82rem; margin: 8px 0 0;">
          ⚠️ {{ formError }}
        </p>

        <div class="form-footer">
          <button class="btn btn-secondary btn-sm" @click="cancelEdit">Hủy</button>
          <button 
            class="btn btn-primary btn-sm" 
            :disabled="!canSaveForm || isSaving" 
            @click="saveRule"
          >
            <span v-if="!isSaving">{{ isCreating ? 'Lưu quy tắc mới' : 'Cập nhật thay đổi' }}</span>
            <span v-else>Đang lưu...</span>
          </button>
        </div>
      </div>

      <!-- Rules List Body -->
      <div class="modal-body rules-scroll-list">
        <!-- Loading State -->
        <div v-if="isLoading" class="loading-state">
          <div class="btn-spinner"></div>
          <span>Đang tải danh sách quy tắc...</span>
        </div>

        <!-- Empty State -->
        <div v-else-if="filteredRules.length === 0" class="empty-rules-state">
          <svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" class="empty-icon">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <p class="empty-title">Không tìm thấy quy tắc nào phù hợp</p>
          <span class="empty-desc">Thử thay đổi bộ lọc Page / từ khóa hoặc bấm "Thêm quy tắc mới"</span>
        </div>

        <!-- Rules Cards Stream -->
        <div v-else class="rules-list-container">
          <div 
            v-for="rule in filteredRules" 
            :key="rule.id" 
            class="rule-card"
            :class="{ 'is-inactive': !rule.active }"
          >
            <!-- Card Top Bar: Category, Page Badge, Active Toggle & Actions -->
            <div class="card-header-bar">
              <div class="header-left-group">
                <span class="category-badge" :class="getCategoryClass(rule.category)">
                  {{ rule.category }}
                </span>
                <span class="page-scope-badge" :class="{ 'is-global': !rule.page_id }">
                  {{ getPageLabel(rule.page_id) }}
                </span>
                <span class="time-stamp">{{ formatTime(rule.created_at) }}</span>
              </div>

              <div class="header-right-group">
                <!-- Toggle Active Switch -->
                <label class="switch-control" :title="rule.active ? 'Đang kích hoạt trong RAG prompt' : 'Đang tạm dừng'">
                  <input 
                    type="checkbox" 
                    :checked="rule.active" 
                    @change="handleToggleActive(rule)"
                  />
                  <span class="slider"></span>
                  <span class="switch-label">{{ rule.active ? 'BẬT' : 'TẮT' }}</span>
                </label>

                <!-- Edit Button -->
                <button class="icon-btn" title="Chỉnh sửa quy tắc" @click="startEdit(rule)">
                  <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/>
                    <path d="m15 5 4 4"/>
                  </svg>
                </button>

                <!-- Delete Button -->
                <button class="icon-btn btn-danger-icon" title="Xóa quy tắc" @click="confirmDelete(rule)">
                  <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M3 6h18"/>
                    <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/>
                    <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/>
                  </svg>
                </button>
              </div>
            </div>

            <!-- Generalized Rule (Main Point) -->
            <div class="rule-content-block">
              <div class="rule-main-text">
                <span class="block-label">💡 Quy tắc suy luận (Meta-Rule):</span>
                <p class="rule-body-text">{{ rule.generalized_rule }}</p>
              </div>
            </div>

            <!-- Root Cause / Reason -->
            <div v-if="rule.root_cause" class="meta-info-row">
              <span class="meta-label">⚠️ Nguyên nhân lỗi:</span>
              <span class="meta-val">{{ rule.root_cause }}</span>
            </div>

            <!-- Trigger Condition -->
            <div v-if="rule.trigger_condition" class="meta-info-row">
              <span class="meta-label">🎯 Kích hoạt khi:</span>
              <span class="meta-val">{{ rule.trigger_condition }}</span>
            </div>

            <!-- Retrieval Keywords Tags -->
            <div v-if="rule.retrieval_keywords && rule.retrieval_keywords.length > 0" class="keywords-bar">
              <span class="meta-label">🏷️ Từ khóa:</span>
              <div class="kw-chips">
                <span v-for="(kw, kidx) in rule.retrieval_keywords" :key="kidx" class="kw-chip">
                  {{ kw }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Delete Confirmation Modal Dialog -->
      <div v-if="deletingTargetRule" class="confirm-overlay" @click.self="deletingTargetRule = null">
        <div class="confirm-box">
          <h4 class="confirm-title text-danger">Xác nhận xóa quy tắc</h4>
          <p class="confirm-msg">
            Bạn có chắc chắn muốn xóa quy tắc này khỏi AI Playbook? Hành động này không thể hoàn tác.
          </p>
          <div class="confirm-quote">
            "{{ deletingTargetRule.generalized_rule }}"
          </div>
          <div class="confirm-actions">
            <button class="btn btn-secondary btn-sm" :disabled="isDeleting" @click="deletingTargetRule = null">Hủy</button>
            <button class="btn btn-danger btn-sm" :disabled="isDeleting" @click="executeDelete">
              <span v-if="!isDeleting">Xác nhận xóa</span>
              <span v-else>Đang xóa...</span>
            </button>
          </div>
        </div>
      </div>

      <!-- Modal Footer Summary -->
      <div class="modal-footer">
        <div class="stats-summary">
          <span class="dot-active"></span>
          <span>
            Tổng cộng: <strong>{{ rules.length }} quy tắc</strong> · 
            <strong>{{ activeCount }}</strong> đang kích hoạt áp dụng vào RAG prompt
          </span>
        </div>
        <button class="btn btn-secondary" @click="handleClose">Đóng</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'
import { 
  getMockRules, 
  createMockRule, 
  updateMockRule, 
  deleteMockRule 
} from '../mockData'

const props = defineProps({
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
  }
})

const emit = defineEmits(['close', 'rules-changed'])

const rules = ref([])
const isLoading = ref(false)
const searchQuery = ref('')
const selectedCategory = ref('')
const selectedPageFilter = ref(props.activePageId || 'ALL')
const statusFilter = ref('all') // 'all' | 'active' | 'inactive'

const isCreating = ref(false)
const editingRule = ref(null)
const isSaving = ref(false)
const formError = ref('')
const formRule = ref({
  generalized_rule: '',
  root_cause: '',
  trigger_condition: '',
  category: 'POLICY',
  page_id: props.activePageId || ''
})
const formKeywordsText = ref('')

const deletingTargetRule = ref(null)
const isDeleting = ref(false)

const activeCount = computed(() => rules.value.filter(r => r.active).length)
const inactiveCount = computed(() => rules.value.filter(r => !r.active).length)
const globalRulesCount = computed(() => rules.value.filter(r => !r.page_id).length)

const getPageRulesCount = (pageId) => {
  return rules.value.filter(r => r.page_id === pageId).length
}

const getPageLabel = (pageId) => {
  if (!pageId) return '🌐 Toàn hệ thống'
  const found = (props.pages || []).find(p => p.id === pageId)
  return found ? `📂 Theo Trang: ${found.name}` : '📂 Theo Trang'
}

const matchesCategoryGroup = (ruleCat, filterGroup) => {
  if (!filterGroup) return true
  const cat = (ruleCat || '').toUpperCase()
  if (filterGroup === 'PRICING') {
    return cat === 'PRICING' || cat === 'PRICING_CALCULATION' || cat === 'TABLE_LOOKUP'
  }
  if (filterGroup === 'DISCOUNT') {
    return cat === 'DISCOUNT'
  }
  if (filterGroup === 'POLICY') {
    return cat === 'POLICY' || cat === 'TEMPORAL_VALIDITY'
  }
  if (filterGroup === 'GENERAL') {
    return cat === 'GENERAL' || cat === 'DATA_INTERPRETATION' || !cat
  }
  return cat === filterGroup
}

const categoryCounts = computed(() => {
  return {
    all: rules.value.length,
    pricing: rules.value.filter(r => matchesCategoryGroup(r.category, 'PRICING')).length,
    discount: rules.value.filter(r => matchesCategoryGroup(r.category, 'DISCOUNT')).length,
    policy: rules.value.filter(r => matchesCategoryGroup(r.category, 'POLICY')).length,
    general: rules.value.filter(r => matchesCategoryGroup(r.category, 'GENERAL')).length
  }
})

const filteredRules = computed(() => {
  return rules.value.filter(r => {
    // 0. Page filter
    if (selectedPageFilter.value === 'GLOBAL') {
      if (r.page_id) return false
    } else if (selectedPageFilter.value !== 'ALL') {
      if (r.page_id !== selectedPageFilter.value && r.page_id != null) return false
    }

    // 1. Category filter
    if (selectedCategory.value && !matchesCategoryGroup(r.category, selectedCategory.value)) {
      return false
    }

    // 2. Status filter
    if (statusFilter.value === 'active' && !r.active) return false
    if (statusFilter.value === 'inactive' && r.active) return false

    // 3. Search query
    if (searchQuery.value.trim()) {
      const q = searchQuery.value.trim().toLowerCase()
      const matchText = (
        (r.generalized_rule || '') + ' ' +
        (r.root_cause || '') + ' ' +
        (r.trigger_condition || '') + ' ' +
        (r.retrieval_keywords ? r.retrieval_keywords.join(' ') : '')
      ).toLowerCase()
      if (!matchText.includes(q)) return false
    }

    return true
  })
})

const canSaveForm = computed(() => {
  return formRule.value.generalized_rule && formRule.value.generalized_rule.trim().length >= 5
})

const fetchRules = async () => {
  isLoading.value = true
  try {
    if (props.isConnected) {
      const list = await api.getRules()
      rules.value = list
    } else {
      rules.value = getMockRules()
    }
  } catch (err) {
    console.warn('Fetch rules fallback to mock:', err)
    rules.value = getMockRules()
  } finally {
    isLoading.value = false
  }
}

const handleSearch = () => {
  // Realtime computed filtering handles this
}

const setCategory = (cat) => {
  selectedCategory.value = cat
}

const setStatusFilter = (st) => {
  statusFilter.value = st
}

const handleToggleActive = async (rule) => {
  const newActive = !rule.active
  rule.active = newActive

  try {
    if (props.isConnected) {
      await api.updateRule(rule.id, {
        category: rule.category,
        page_id: rule.page_id,
        trigger_condition: rule.trigger_condition,
        root_cause: rule.root_cause,
        generalized_rule: rule.generalized_rule,
        retrieval_keywords: rule.retrieval_keywords,
        active: newActive
      })
    } else {
      updateMockRule(rule.id, { active: newActive })
    }
    emit('rules-changed', { rules: rules.value })
  } catch (err) {
    console.error('Toggle active error:', err)
    // Revert on error
    rule.active = !newActive
  }
}

const openCreateForm = () => {
  editingRule.value = null
  isCreating.value = true
  formError.value = ''
  formRule.value = {
    generalized_rule: '',
    root_cause: '',
    trigger_condition: '',
    category: 'POLICY',
    page_id: props.activePageId || ''
  }
  formKeywordsText.value = ''
}

const startEdit = (rule) => {
  isCreating.value = false
  editingRule.value = rule
  formError.value = ''
  formRule.value = {
    generalized_rule: rule.generalized_rule || '',
    root_cause: rule.root_cause || '',
    trigger_condition: rule.trigger_condition || '',
    category: rule.category || 'POLICY',
    page_id: rule.page_id || ''
  }
  formKeywordsText.value = rule.retrieval_keywords ? rule.retrieval_keywords.join(', ') : ''
}

const cancelEdit = () => {
  isCreating.value = false
  editingRule.value = null
  formError.value = ''
}

const saveRule = async () => {
  if (!canSaveForm.value || isSaving.value) return

  isSaving.value = true
  formError.value = ''
  const keywords = formKeywordsText.value
    .split(',')
    .map(s => s.trim())
    .filter(Boolean)

  const trimmedRule = (formRule.value.generalized_rule || '').trim()
  const autoTrigger = (formRule.value.trigger_condition || '').trim()
    || ('Khi xử lý tình huống: ' + (trimmedRule.length > 80 ? trimmedRule.slice(0, 80) + '...' : trimmedRule))

  const payload = {
    ...formRule.value,
    generalized_rule: trimmedRule,
    trigger_condition: autoTrigger,
    category: formRule.value.category || 'POLICY',
    page_id: formRule.value.page_id || null,
    retrieval_keywords: keywords,
    active: editingRule.value ? editingRule.value.active : true
  }

  try {
    if (isCreating.value) {
      if (props.isConnected) {
        const created = await api.createRule(payload)
        rules.value.unshift(created)
      } else {
        const created = createMockRule(payload)
        rules.value.unshift(created)
      }
      selectedCategory.value = ''
    } else if (editingRule.value) {
      if (props.isConnected) {
        const updated = await api.updateRule(editingRule.value.id, payload)
        const idx = rules.value.findIndex(r => r.id === editingRule.value.id)
        if (idx !== -1) rules.value[idx] = { ...rules.value[idx], ...updated }
      } else {
        const updated = updateMockRule(editingRule.value.id, payload)
        const idx = rules.value.findIndex(r => r.id === editingRule.value.id)
        if (idx !== -1) rules.value[idx] = { ...rules.value[idx], ...updated }
      }
    }

    cancelEdit()
    emit('rules-changed', { rules: rules.value })
  } catch (err) {
    console.error('Save rule error:', err)
    formError.value = err?.message || 'Không thể lưu quy tắc. Vui lòng kiểm tra lại thông tin.'
  } finally {
    isSaving.value = false
  }
}

const confirmDelete = (rule) => {
  deletingTargetRule.value = rule
}

const executeDelete = async () => {
  if (!deletingTargetRule.value || isDeleting.value) return

  isDeleting.value = true
  const id = deletingTargetRule.value.id

  try {
    if (props.isConnected) {
      await api.deleteRule(id)
    } else {
      deleteMockRule(id)
    }

    rules.value = rules.value.filter(r => r.id !== id)
    deletingTargetRule.value = null
    emit('rules-changed', { rules: rules.value })
  } catch (err) {
    console.error('Delete rule error:', err)
  } finally {
    isDeleting.value = false
  }
}

const getCategoryClass = (cat) => {
  switch ((cat || '').toUpperCase()) {
    case 'PRICING':
    case 'PRICING_CALCULATION':
    case 'TABLE_LOOKUP':
      return 'cat-pricing'
    case 'DISCOUNT':
      return 'cat-discount'
    case 'POLICY':
    case 'TEMPORAL_VALIDITY':
      return 'cat-policy'
    default:
      return 'cat-general'
  }
}

const formatTime = (iso) => {
  if (!iso) return ''
  try {
    const d = new Date(iso)
    return d.toLocaleDateString('vi-VN', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric'
    })
  } catch {
    return ''
  }
}

const handleClose = () => {
  emit('close')
}

onMounted(() => {
  fetchRules()
})
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

.playbook-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-active);
  border-radius: var(--radius-lg);
  width: 100%;
  max-width: 860px;
  height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 50px -10px rgba(0, 0, 0, 0.7), 0 0 30px rgba(99, 102, 241, 0.2);
  overflow: hidden;
  position: relative;
}

/* Header */
.modal-header {
  padding: 18px 24px;
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

.icon-brain-box {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  background: rgba(168, 85, 247, 0.15);
  color: #c084fc;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(168, 85, 247, 0.3);
}

.title-with-badge {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-title {
  font-size: 1.15rem;
  font-weight: 600;
  color: var(--text-main);
  letter-spacing: -0.01em;
}

.badge-accent {
  background: rgba(99, 102, 241, 0.15);
  color: #a5b4fc;
  border: 1px solid rgba(99, 102, 241, 0.3);
  font-size: 0.72rem;
  padding: 2px 8px;
  border-radius: var(--radius-full);
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

.btn-close:hover {
  color: var(--text-main);
  background: rgba(255, 255, 255, 0.1);
}

/* Toolbar */
.toolbar-section {
  padding: 14px 24px;
  border-bottom: 1px solid var(--border-subtle);
  background: rgba(255, 255, 255, 0.01);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-action-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.search-input-wrapper {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 12px;
  color: var(--text-dim);
  pointer-events: none;
}

.search-input {
  width: 100%;
  background: var(--bg-input);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 8px 36px 8px 36px;
  color: var(--text-main);
  font-family: var(--font-sans);
  font-size: 0.86rem;
  outline: none;
  transition: border-color 0.2s;
}

.search-input:focus {
  border-color: var(--accent-primary);
}

.btn-clear-search {
  position: absolute;
  right: 10px;
  background: transparent;
  border: none;
  color: var(--text-dim);
  font-size: 1rem;
  cursor: pointer;
}

.btn-sm {
  padding: 6px 12px;
  font-size: 0.82rem;
  white-space: nowrap;
}

/* Filter Chips */
.filter-chips-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.filter-group {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.filter-group-label {
  font-size: 0.75rem;
  color: var(--text-dim);
  font-weight: 500;
  margin-right: 2px;
}

.pill-filter {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--border-subtle);
  color: var(--text-muted);
  border-radius: var(--radius-full);
  padding: 3px 10px;
  font-size: 0.76rem;
  cursor: pointer;
  transition: all 0.15s ease;
}

.pill-filter:hover {
  background: rgba(255, 255, 255, 0.08);
  color: var(--text-main);
}

.pill-filter.active {
  background: rgba(99, 102, 241, 0.2);
  border-color: var(--accent-primary);
  color: #ffffff;
  font-weight: 600;
}

.pill-emerald.active {
  background: rgba(16, 185, 129, 0.2);
  border-color: #34d399;
  color: #34d399;
}

.pill-dim.active {
  background: rgba(100, 116, 139, 0.25);
  border-color: #64748b;
  color: #cbd5e1;
}

/* Form Container */
.rule-form-container {
  padding: 16px 24px;
  background: rgba(99, 102, 241, 0.06);
  border-bottom: 1px solid var(--border-active);
  animation: slideDown 0.2s ease-out;
}

@keyframes slideDown {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}

.form-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.form-title {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--text-main);
}

.btn-text-sm {
  background: transparent;
  border: none;
  color: var(--text-muted);
  font-size: 0.8rem;
  cursor: pointer;
}

.btn-text-sm:hover {
  color: var(--text-main);
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.full-width {
  grid-column: 1 / -1;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  font-size: 0.78rem;
  font-weight: 600;
  color: var(--text-muted);
}

.required::after {
  content: ' *';
  color: var(--status-failed-text);
}

.form-input, .form-textarea, .form-select {
  background: var(--bg-input);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
  color: var(--text-main);
  font-family: var(--font-sans);
  font-size: 0.85rem;
  outline: none;
}

.form-textarea {
  resize: vertical;
}

.form-input:focus, .form-textarea:focus, .form-select:focus {
  border-color: var(--accent-primary);
}

.form-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}

/* Scrollable Rules List */
.rules-scroll-list {
  flex: 1;
  padding: 16px 24px;
  overflow-y: auto;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 240px;
  gap: 12px;
  color: var(--text-muted);
  font-size: 0.88rem;
}

.empty-rules-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 260px;
  gap: 8px;
  color: var(--text-dim);
}

.empty-icon {
  opacity: 0.4;
  margin-bottom: 4px;
}

.empty-title {
  font-size: 0.95rem;
  font-weight: 500;
  color: var(--text-muted);
}

.empty-desc {
  font-size: 0.8rem;
}

.rules-list-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* Rule Card */
.rule-card {
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: all 0.2s ease;
}

.rule-card:hover {
  background: var(--bg-card-hover);
  border-color: rgba(99, 102, 241, 0.3);
}

.rule-card.is-inactive {
  opacity: 0.6;
  border-color: rgba(255, 255, 255, 0.04);
}

.card-header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-left-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.category-badge {
  font-size: 0.72rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

.page-scope-badge {
  font-size: 0.72rem;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: var(--radius-full);
  background: rgba(99, 102, 241, 0.14);
  color: #a5b4fc;
  border: 1px solid rgba(99, 102, 241, 0.3);
}

.page-scope-badge.is-global {
  background: rgba(16, 185, 129, 0.12);
  color: #6ee7b7;
  border: 1px solid rgba(16, 185, 129, 0.3);
}

.cat-pricing {
  background: rgba(234, 179, 8, 0.15);
  color: #fde047;
  border: 1px solid rgba(234, 179, 8, 0.35);
}

.cat-discount {
  background: rgba(168, 85, 247, 0.15);
  color: #d8b4fe;
  border: 1px solid rgba(168, 85, 247, 0.35);
}

.cat-policy {
  background: rgba(59, 130, 246, 0.15);
  color: #93c5fd;
  border: 1px solid rgba(59, 130, 246, 0.35);
}

.cat-general {
  background: rgba(148, 163, 184, 0.15);
  color: #cbd5e1;
  border: 1px solid rgba(148, 163, 184, 0.35);
}

.time-stamp {
  font-size: 0.74rem;
  color: var(--text-dim);
}

.header-right-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* Switch control */
.switch-control {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.switch-control input {
  display: none;
}

.slider {
  width: 32px;
  height: 18px;
  background-color: #334155;
  border-radius: 20px;
  position: relative;
  transition: background-color 0.2s;
}

.slider::after {
  content: '';
  position: absolute;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background-color: #ffffff;
  top: 2px;
  left: 2px;
  transition: transform 0.2s;
}

.switch-control input:checked + .slider {
  background-color: #10b981;
}

.switch-control input:checked + .slider::after {
  transform: translateX(14px);
}

.switch-label {
  font-size: 0.72rem;
  font-weight: 700;
  color: var(--text-muted);
}

.switch-control input:checked ~ .switch-label {
  color: #34d399;
}

.icon-btn {
  background: transparent;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  padding: 4px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}

.icon-btn:hover {
  color: var(--text-main);
  background: rgba(255, 255, 255, 0.08);
}

.btn-danger-icon:hover {
  color: #fb7185;
  background: rgba(244, 63, 94, 0.15);
}

/* Rule Text */
.rule-content-block {
  background: rgba(255, 255, 255, 0.02);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
}

.block-label {
  font-size: 0.75rem;
  font-weight: 600;
  color: #a5b4fc;
}

.rule-body-text {
  font-size: 0.88rem;
  font-weight: 500;
  color: var(--text-main);
  margin-top: 2px;
  line-height: 1.5;
}

.meta-info-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 0.8rem;
  line-height: 1.4;
}

.meta-label {
  font-size: 0.74rem;
  font-weight: 600;
  color: var(--text-dim);
  white-space: nowrap;
}

.meta-val {
  color: var(--text-muted);
}

.keywords-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.kw-chips {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}

.kw-chip {
  font-size: 0.7rem;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid var(--border-subtle);
  padding: 1px 6px;
  border-radius: 4px;
  color: #cbd5e1;
}

/* Confirm Dialog */
.confirm-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 20;
  padding: 20px;
}

.confirm-box {
  background: var(--bg-secondary);
  border: 1px solid var(--status-failed-border);
  border-radius: var(--radius-md);
  padding: 20px;
  max-width: 480px;
  width: 100%;
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.confirm-title {
  font-size: 1rem;
  font-weight: 600;
}

.text-danger {
  color: #fb7185;
}

.confirm-msg {
  font-size: 0.85rem;
  color: var(--text-muted);
  line-height: 1.4;
}

.confirm-quote {
  font-size: 0.82rem;
  font-style: italic;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  background: rgba(244, 63, 94, 0.08);
  border-left: 3px solid #fb7185;
  color: #fecdd3;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}

/* Footer */
.modal-footer {
  padding: 14px 24px;
  border-top: 1px solid var(--border-subtle);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.02);
}

.stats-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.82rem;
  color: var(--text-muted);
}

.dot-active {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 8px rgba(16, 185, 129, 0.6);
}

.btn-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(255, 255, 255, 0.2);
  border-top-color: var(--accent-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
