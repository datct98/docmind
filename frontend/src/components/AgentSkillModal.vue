<template>
  <div class="modal-backdrop" @click.self="handleClose">
    <div class="modal-card skill-modal-card">
      <!-- Modal Header -->
      <div class="modal-header">
        <div class="modal-title-group">
          <div class="icon-skill-box">
            <svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
            </svg>
          </div>
          <div>
            <div class="title-with-badge">
              <h3 class="modal-title">Kho Kỹ năng Chuyên gia AI (Agent Skill Registry)</h3>
              <span class="badge badge-accent">{{ skills.length }} kỹ năng</span>
            </div>
            <p class="modal-subtitle">
              Hệ thống plugin kỹ năng Tâm lý học khách hàng, Chiến thuật Chốt Sale &amp; Nghệ thuật Giao tiếp tự động kích hoạt theo thời gian thực
            </p>
          </div>
        </div>
        <button class="btn-close" @click="handleClose">×</button>
      </div>

      <!-- Toolbar & Filters -->
      <div class="toolbar-section">
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
              placeholder="Tìm kỹ năng theo tên, mã code, tín hiệu kích hoạt, chiến thuật..."
            />
            <button v-if="searchQuery" class="btn-clear-search" @click="searchQuery = ''">×</button>
          </div>

          <button class="btn btn-primary btn-sm" @click="openCreateForm">
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="12" y1="5" x2="12" y2="19"/>
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            <span>Thêm kỹ năng mới</span>
          </button>
        </div>

        <!-- Page Scope Filter -->
        <div v-if="pages && pages.length > 0" class="filter-chips-row">
          <div class="filter-group">
            <span class="filter-group-label">Phạm vi Page:</span>
            <button
              class="pill-filter"
              :class="{ active: selectedPageFilter === 'ALL' }"
              @click="selectedPageFilter = 'ALL'"
            >
              Tất cả ({{ skills.length }})
            </button>
            <button
              class="pill-filter"
              :class="{ active: selectedPageFilter === 'GLOBAL' }"
              @click="selectedPageFilter = 'GLOBAL'"
            >
              🌐 Toàn hệ thống ({{ globalSkillsCount }})
            </button>
            <button
              v-for="p in pages"
              :key="p.id"
              class="pill-filter"
              :class="{ active: selectedPageFilter === p.id }"
              @click="selectedPageFilter = p.id"
            >
              📂 {{ p.name }} ({{ getPageSkillsCount(p.id) }})
            </button>
          </div>
        </div>

        <!-- Category & Status Filters -->
        <div class="filter-chips-row">
          <div class="filter-group">
            <span class="filter-group-label">Trụ cột Kỹ năng:</span>
            <button
              class="pill-filter"
              :class="{ active: selectedCategory === '' }"
              @click="selectedCategory = ''"
            >
              Tất cả ({{ categoryCounts.all }})
            </button>
            <button
              class="pill-filter cat-psych"
              :class="{ active: selectedCategory === 'PSYCHOLOGY' }"
              @click="selectedCategory = 'PSYCHOLOGY'"
            >
              🧠 Tâm lý học ({{ categoryCounts.psychology }})
            </button>
            <button
              class="pill-filter cat-sales"
              :class="{ active: selectedCategory === 'SALES_STRATEGY' }"
              @click="selectedCategory = 'SALES_STRATEGY'"
            >
              🎯 Chiến thuật Sale ({{ categoryCounts.sales }})
            </button>
            <button
              class="pill-filter cat-comm"
              :class="{ active: selectedCategory === 'COMMUNICATION' }"
              @click="selectedCategory = 'COMMUNICATION'"
            >
              💬 Giao tiếp ({{ categoryCounts.comm }})
            </button>
          </div>

          <div class="filter-group">
            <span class="filter-group-label">Trạng thái:</span>
            <button
              class="pill-filter"
              :class="{ active: selectedStatus === 'ALL' }"
              @click="selectedStatus = 'ALL'"
            >
              Tất cả
            </button>
            <button
              class="pill-filter"
              :class="{ active: selectedStatus === 'ACTIVE' }"
              @click="selectedStatus = 'ACTIVE'"
            >
              Đang bật ({{ activeCount }})
            </button>
            <button
              class="pill-filter"
              :class="{ active: selectedStatus === 'INACTIVE' }"
              @click="selectedStatus = 'INACTIVE'"
            >
              Đang tắt ({{ skills.length - activeCount }})
            </button>
          </div>
        </div>
      </div>

      <!-- Create / Edit Form Drawer -->
      <div v-if="showForm" class="skill-form-panel">
        <div class="form-panel-header">
          <h4>{{ editingSkillId ? '✏️ Chỉnh sửa Kỹ năng Agent' : '✨ Thêm Kỹ năng Chuyên gia Mới' }}</h4>
          <button class="btn-cancel-link" @click="closeForm">Đóng biểu mẫu</button>
        </div>

        <div class="form-grid">
          <div class="form-group">
            <label class="form-label">Tên kỹ năng <span class="required">*</span></label>
            <input
              v-model="formData.name"
              type="text"
              class="form-input"
              placeholder="VD: Thấu cảm sâu Nỗi đau Mãn tính & Lâu năm"
            />
          </div>

          <div class="form-group">
            <label class="form-label">Trụ cột Kỹ năng</label>
            <select v-model="formData.category" class="form-select">
              <option value="PSYCHOLOGY">🧠 Tâm lý học khách hàng (PSYCHOLOGY)</option>
              <option value="SALES_STRATEGY">🎯 Chiến thuật Tư vấn &amp; Chốt Sale (SALES_STRATEGY)</option>
              <option value="COMMUNICATION">💬 Nghệ thuật Giao tiếp &amp; Ngôn từ (COMMUNICATION)</option>
            </select>
          </div>

          <div class="form-group">
            <label class="form-label">Phạm vi áp dụng</label>
            <select v-model="formData.page_id" class="form-select">
              <option :value="null">🌐 Toàn hệ thống (Mọi Fanpage / Kênh)</option>
              <option v-for="p in pages" :key="p.id" :value="p.id">
                📂 Chỉ áp dụng cho: {{ p.name }}
              </option>
            </select>
          </div>

          <div class="form-group">
            <label class="form-label">Độ ưu tiên (1 - 100)</label>
            <input
              v-model.number="formData.priority"
              type="number"
              min="1"
              max="100"
              class="form-input"
            />
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">Mô tả mục tiêu kỹ năng</label>
          <input
            v-model="formData.description"
            type="text"
            class="form-input"
            placeholder="Mô tả ngắn gọn khi nào và tại sao Agent cần kích hoạt kỹ năng này..."
          />
        </div>

        <div class="form-group">
          <label class="form-label">Tín hiệu kích hoạt tự động (phân tách bằng dấu phẩy)</label>
          <input
            v-model="formData.trigger_signals"
            type="text"
            class="form-input"
            placeholder="VD: lâu năm, mãn tính, chữa nhiều nơi, sợ tái phát, giá cao, đắt..."
          />
        </div>

        <div class="form-group">
          <label class="form-label">Chỉ dẫn Chiến thuật cho AI (Tactical Instructions) <span class="required">*</span></label>
          <textarea
            v-model="formData.tactical_instructions"
            rows="3"
            class="form-textarea"
            placeholder="Hướng dẫn chi tiết cách AI xử lý tâm lý và dẫn dắt khách hàng khi kỹ năng này được kích hoạt..."
          ></textarea>
        </div>

        <div class="form-grid">
          <div class="form-group">
            <label class="form-label">Câu mẫu Chuẩn mực (Good Example)</label>
            <textarea
              v-model="formData.good_example"
              rows="2"
              class="form-textarea"
              placeholder="Ví dụ câu trả lời mẫu tinh tế..."
            ></textarea>
          </div>

          <div class="form-group">
            <label class="form-label">Điều Cấm kỵ (Anti-Pattern)</label>
            <textarea
              v-model="formData.anti_pattern"
              rows="2"
              class="form-textarea"
              placeholder="Những lỗi giao tiếp tuyệt đối tránh..."
            ></textarea>
          </div>
        </div>

        <div v-if="formError" class="form-error">{{ formError }}</div>

        <div class="form-actions">
          <button class="btn btn-ghost btn-sm" @click="closeForm">Hủy</button>
          <button class="btn btn-primary btn-sm" :disabled="saving" @click="saveSkill">
            {{ saving ? 'Đang lưu...' : (editingSkillId ? 'Lưu cập nhật' : 'Tạo kỹ năng') }}
          </button>
        </div>
      </div>

      <!-- Skills List Body -->
      <div class="skills-list-body">
        <div v-if="loading" class="empty-state">
          <p>Đang tải Kho Kỹ năng Agent...</p>
        </div>

        <div v-else-if="filteredSkills.length === 0" class="empty-state">
          <p>Không tìm thấy kỹ năng nào khớp với bộ lọc hiện tại.</p>
        </div>

        <div v-else class="skills-grid">
          <div
            v-for="skill in filteredSkills"
            :key="skill.id || skill.code"
            class="skill-card"
            :class="{ 'skill-inactive': !skill.active }"
          >
            <div class="skill-card-header">
              <div class="skill-badges">
                <span class="cat-badge" :class="getCategoryClass(skill.category)">
                  {{ getCategoryLabel(skill.category) }}
                </span>
                <span class="scope-badge" :class="skill.page_id ? 'scope-page' : 'scope-global'">
                  {{ skill.page_id ? `📂 ${getPageName(skill.page_id)}` : '🌐 Toàn hệ thống' }}
                </span>
                <span class="priority-badge" title="Độ ưu tiên định tuyến">
                  ⚡ Ưu tiên {{ skill.priority }}
                </span>
                <span class="code-badge">{{ skill.code }}</span>
              </div>

              <div class="skill-controls">
                <label class="switch-toggle" :title="skill.active ? 'Đang kích hoạt — Bấm để tắt' : 'Đang tắt — Bấm để bật'">
                  <input
                    type="checkbox"
                    :checked="skill.active"
                    @change="toggleSkillActive(skill)"
                  />
                  <span class="switch-slider"></span>
                </label>
                <button class="btn-icon" title="Chỉnh sửa kỹ năng" @click="openEditForm(skill)">
                  ✏️
                </button>
                <button class="btn-icon btn-icon-danger" title="Xóa kỹ năng" @click="handleDeleteSkill(skill)">
                  🗑️
                </button>
              </div>
            </div>

            <h4 class="skill-name">{{ skill.name }}</h4>
            <p v-if="skill.description" class="skill-desc">{{ skill.description }}</p>

            <div class="skill-detail-box">
              <div class="detail-row">
                <span class="detail-label">🎯 Chỉ dẫn Chiến thuật:</span>
                <p class="detail-text">{{ skill.tactical_instructions }}</p>
              </div>

              <div v-if="skill.trigger_signals" class="detail-row">
                <span class="detail-label">📡 Tín hiệu kích hoạt:</span>
                <div class="signal-chips">
                  <span
                    v-for="(sig, idx) in splitSignals(skill.trigger_signals)"
                    :key="idx"
                    class="signal-chip"
                  >
                    {{ sig }}
                  </span>
                </div>
              </div>

              <div v-if="skill.good_example || skill.anti_pattern" class="examples-grid">
                <div v-if="skill.good_example" class="example-box good-box">
                  <span class="example-title">✅ Câu mẫu chuẩn mực:</span>
                  <p>{{ skill.good_example }}</p>
                </div>
                <div v-if="skill.anti_pattern" class="example-box anti-box">
                  <span class="example-title">🚫 Điều cấm kỵ:</span>
                  <p>{{ skill.anti_pattern }}</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Modal Footer -->
      <div class="modal-footer">
        <span class="footer-hint">
          💡 <strong>Cơ chế hoạt động:</strong> Ở mỗi lượt tin nhắn, <code>CustomerInsightEngine</code> đọc vị tâm lý khách hàng và tự động chọn <strong>Top 3 Kỹ năng phù hợp nhất</strong> đang bật để bơm vào não bộ tư vấn của Bot.
        </span>
        <button class="btn btn-ghost btn-sm" @click="handleClose">Đóng</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'
import { getMockSkills, createMockSkill, updateMockSkill, deleteMockSkill } from '../mockData.js'

const props = defineProps({
  demoMode: {
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

const emit = defineEmits(['close', 'skills-updated'])

const skills = ref([])
const loading = ref(false)
const searchQuery = ref('')
const selectedCategory = ref('')
const selectedPageFilter = ref('ALL')
const selectedStatus = ref('ALL')

const showForm = ref(false)
const editingSkillId = ref(null)
const saving = ref(false)
const formError = ref('')

const formData = ref({
  name: '',
  code: '',
  category: 'PSYCHOLOGY',
  page_id: props.activePageId || null,
  priority: 85,
  description: '',
  trigger_signals: '',
  tactical_instructions: '',
  good_example: '',
  anti_pattern: '',
  active: true
})

const loadSkills = async () => {
  loading.value = true
  try {
    if (props.demoMode) {
      skills.value = getMockSkills()
    } else {
      skills.value = await api.getSkills()
    }
    emit('skills-updated', skills.value)
  } catch (err) {
    console.warn('Fallback to mock skills:', err)
    skills.value = getMockSkills()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadSkills()
})

const globalSkillsCount = computed(() => skills.value.filter(s => !s.page_id).length)

const getPageSkillsCount = (pageId) => {
  return skills.value.filter(s => s.page_id === pageId).length
}

const getPageName = (pageId) => {
  const found = (props.pages || []).find(p => p.id === pageId)
  return found ? found.name : 'Page riêng'
}

const categoryCounts = computed(() => {
  const list = skills.value
  return {
    all: list.length,
    psychology: list.filter(s => s.category === 'PSYCHOLOGY').length,
    sales: list.filter(s => s.category === 'SALES_STRATEGY').length,
    comm: list.filter(s => s.category === 'COMMUNICATION').length
  }
})

const activeCount = computed(() => skills.value.filter(s => s.active).length)

const filteredSkills = computed(() => {
  return skills.value.filter(s => {
    if (selectedPageFilter.value === 'GLOBAL' && s.page_id) return false
    if (selectedPageFilter.value !== 'ALL' && selectedPageFilter.value !== 'GLOBAL') {
      if (s.page_id !== selectedPageFilter.value) return false
    }
    if (selectedCategory.value && s.category !== selectedCategory.value) return false
    if (selectedStatus.value === 'ACTIVE' && !s.active) return false
    if (selectedStatus.value === 'INACTIVE' && s.active) return false

    if (searchQuery.value.trim()) {
      const q = searchQuery.value.trim().toLowerCase()
      const hay = `${s.name || ''} ${s.code || ''} ${s.description || ''} ${s.trigger_signals || ''} ${s.tactical_instructions || ''}`.toLowerCase()
      if (!hay.includes(q)) return false
    }
    return true
  })
})

const getCategoryLabel = (cat) => {
  if (cat === 'PSYCHOLOGY') return '🧠 Tâm lý học'
  if (cat === 'COMMUNICATION') return '💬 Giao tiếp'
  return '🎯 Chiến thuật Sale'
}

const getCategoryClass = (cat) => {
  if (cat === 'PSYCHOLOGY') return 'badge-psych'
  if (cat === 'COMMUNICATION') return 'badge-comm'
  return 'badge-sales'
}

const splitSignals = (raw) => {
  if (!raw) return []
  return raw.split(/[,;]+/).map(s => s.trim()).filter(Boolean).slice(0, 10)
}

const openCreateForm = () => {
  editingSkillId.value = null
  formError.value = ''
  formData.value = {
    name: '',
    code: '',
    category: selectedCategory.value || 'PSYCHOLOGY',
    page_id: (selectedPageFilter.value !== 'ALL' && selectedPageFilter.value !== 'GLOBAL')
      ? selectedPageFilter.value
      : (props.activePageId || null),
    priority: 85,
    description: '',
    trigger_signals: '',
    tactical_instructions: '',
    good_example: '',
    anti_pattern: '',
    active: true
  }
  showForm.value = true
}

const openEditForm = (skill) => {
  editingSkillId.value = skill.id
  formError.value = ''
  formData.value = {
    name: skill.name || '',
    code: skill.code || '',
    category: skill.category || 'SALES_STRATEGY',
    page_id: skill.page_id || null,
    priority: skill.priority || 80,
    description: skill.description || '',
    trigger_signals: skill.trigger_signals || '',
    tactical_instructions: skill.tactical_instructions || '',
    good_example: skill.good_example || '',
    anti_pattern: skill.anti_pattern || '',
    active: skill.active !== false
  }
  showForm.value = true
}

const closeForm = () => {
  showForm.value = false
  editingSkillId.value = null
  formError.value = ''
}

const saveSkill = async () => {
  if (!formData.value.name.trim()) {
    formError.value = 'Vui lòng nhập tên kỹ năng.'
    return
  }
  if (!formData.value.tactical_instructions.trim()) {
    formError.value = 'Vui lòng nhập chỉ dẫn chiến thuật cho AI.'
    return
  }

  saving.value = true
  formError.value = ''
  try {
    const payload = { ...formData.value }
    if (editingSkillId.value) {
      if (props.demoMode) {
        updateMockSkill(editingSkillId.value, payload)
      } else {
        await api.updateSkill(editingSkillId.value, payload)
      }
    } else {
      if (props.demoMode) {
        createMockSkill(payload)
      } else {
        await api.createSkill(payload)
      }
    }
    await loadSkills()
    closeForm()
  } catch (err) {
    formError.value = err.message || 'Không thể lưu kỹ năng.'
  } finally {
    saving.value = false
  }
}

const toggleSkillActive = async (skill) => {
  const newActive = !skill.active
  try {
    if (props.demoMode) {
      updateMockSkill(skill.id, { active: newActive })
    } else {
      await api.updateSkill(skill.id, { active: newActive })
    }
    skill.active = newActive
    emit('skills-updated', skills.value)
  } catch (err) {
    console.error('Lỗi bật/tắt kỹ năng:', err)
  }
}

const handleDeleteSkill = async (skill) => {
  if (!confirm(`Bạn có chắc chắn muốn xóa kỹ năng "${skill.name}"?`)) return
  try {
    if (props.demoMode) {
      deleteMockSkill(skill.id)
    } else {
      await api.deleteSkill(skill.id)
    }
    await loadSkills()
  } catch (err) {
    alert(err.message || 'Lỗi khi xóa kỹ năng')
  }
}

const handleClose = () => {
  emit('close')
}
</script>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(9, 13, 24, 0.78);
  backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1200;
  padding: 20px;
}

.skill-modal-card {
  width: 100%;
  max-width: 1040px;
  max-height: 90vh;
  background: var(--bg-surface, #131b2e);
  border: 1px solid rgba(139, 92, 246, 0.3);
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.55);
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-bottom: 1px solid var(--border-color, rgba(255, 255, 255, 0.08));
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.12), rgba(59, 130, 246, 0.06));
}

.modal-title-group {
  display: flex;
  align-items: center;
  gap: 14px;
}

.icon-skill-box {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, #8b5cf6, #6366f1);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 16px rgba(139, 92, 246, 0.35);
}

.title-with-badge {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-title {
  margin: 0;
  font-size: 1.15rem;
  font-weight: 700;
  color: var(--text-primary, #f8fafc);
}

.modal-subtitle {
  margin: 4px 0 0;
  font-size: 0.82rem;
  color: var(--text-secondary, #94a3b8);
}

.btn-close {
  background: transparent;
  border: none;
  color: var(--text-secondary, #94a3b8);
  font-size: 1.6rem;
  cursor: pointer;
  line-height: 1;
  padding: 4px 8px;
  border-radius: 8px;
}

.btn-close:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}

.toolbar-section {
  padding: 14px 24px;
  border-bottom: 1px solid var(--border-color, rgba(255, 255, 255, 0.08));
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: rgba(15, 23, 42, 0.5);
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
  color: var(--text-muted, #64748b);
}

.search-input {
  width: 100%;
  padding: 9px 32px 9px 36px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(15, 23, 42, 0.75);
  color: var(--text-primary, #f8fafc);
  font-size: 0.86rem;
}

.btn-clear-search {
  position: absolute;
  right: 10px;
  background: transparent;
  border: none;
  color: #94a3b8;
  cursor: pointer;
  font-size: 1.1rem;
}

.filter-chips-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.filter-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.filter-group-label {
  font-size: 0.76rem;
  color: var(--text-muted, #64748b);
  font-weight: 600;
  margin-right: 4px;
}

.pill-filter {
  padding: 5px 11px;
  border-radius: 999px;
  font-size: 0.76rem;
  font-weight: 600;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(30, 41, 59, 0.6);
  color: var(--text-secondary, #cbd5e1);
  cursor: pointer;
  transition: all 0.15s ease;
}

.pill-filter:hover {
  border-color: rgba(139, 92, 246, 0.5);
  color: #fff;
}

.pill-filter.active {
  background: linear-gradient(135deg, #8b5cf6, #6366f1);
  border-color: transparent;
  color: #fff;
}

.skill-form-panel {
  padding: 16px 24px;
  background: rgba(30, 41, 59, 0.75);
  border-bottom: 1px solid rgba(139, 92, 246, 0.3);
  max-height: 46vh;
  overflow-y: auto;
}

.form-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.form-panel-header h4 {
  margin: 0;
  font-size: 0.95rem;
  color: #f8fafc;
}

.btn-cancel-link {
  background: none;
  border: none;
  color: #94a3b8;
  font-size: 0.8rem;
  cursor: pointer;
  text-decoration: underline;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
  margin-bottom: 10px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 10px;
}

.form-label {
  font-size: 0.76rem;
  font-weight: 600;
  color: #cbd5e1;
}

.required {
  color: #f87171;
}

.form-input,
.form-select,
.form-textarea {
  padding: 8px 11px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(15, 23, 42, 0.85);
  color: #f8fafc;
  font-size: 0.83rem;
  font-family: inherit;
}

.form-error {
  color: #f87171;
  font-size: 0.8rem;
  margin-bottom: 8px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.skills-list-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 24px;
}

.skills-grid {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.skill-card {
  background: rgba(30, 41, 59, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.09);
  border-radius: 12px;
  padding: 14px 16px;
  transition: all 0.18s ease;
}

.skill-card:hover {
  border-color: rgba(139, 92, 246, 0.45);
  background: rgba(30, 41, 59, 0.8);
}

.skill-card.skill-inactive {
  opacity: 0.58;
}

.skill-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.skill-badges {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.cat-badge {
  padding: 3px 9px;
  border-radius: 6px;
  font-size: 0.72rem;
  font-weight: 700;
}

.badge-psych {
  background: rgba(168, 85, 247, 0.2);
  color: #d8b4fe;
  border: 1px solid rgba(168, 85, 247, 0.35);
}

.badge-sales {
  background: rgba(16, 185, 129, 0.18);
  color: #6ee7b7;
  border: 1px solid rgba(16, 185, 129, 0.35);
}

.badge-comm {
  background: rgba(59, 130, 246, 0.18);
  color: #93c5fd;
  border: 1px solid rgba(59, 130, 246, 0.35);
}

.scope-badge {
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 0.71rem;
  font-weight: 600;
}

.scope-global {
  background: rgba(100, 116, 139, 0.2);
  color: #cbd5e1;
}

.scope-page {
  background: rgba(245, 158, 11, 0.18);
  color: #fcd34d;
}

.priority-badge {
  font-size: 0.71rem;
  color: #fbbf24;
  background: rgba(245, 158, 11, 0.12);
  padding: 2px 7px;
  border-radius: 6px;
}

.code-badge {
  font-family: monospace;
  font-size: 0.7rem;
  color: #94a3b8;
  background: rgba(15, 23, 42, 0.6);
  padding: 2px 6px;
  border-radius: 4px;
}

.skill-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-icon {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 6px;
  padding: 4px 7px;
  cursor: pointer;
  font-size: 0.8rem;
}

.btn-icon:hover {
  background: rgba(255, 255, 255, 0.14);
}

.switch-toggle {
  position: relative;
  display: inline-block;
  width: 36px;
  height: 20px;
}

.switch-toggle input {
  opacity: 0;
  width: 0;
  height: 0;
}

.switch-slider {
  position: absolute;
  cursor: pointer;
  inset: 0;
  background-color: #475569;
  transition: 0.2s;
  border-radius: 20px;
}

.switch-slider:before {
  position: absolute;
  content: "";
  height: 14px;
  width: 14px;
  left: 3px;
  bottom: 3px;
  background-color: white;
  transition: 0.2s;
  border-radius: 50%;
}

.switch-toggle input:checked + .switch-slider {
  background-color: #8b5cf6;
}

.switch-toggle input:checked + .switch-slider:before {
  transform: translateX(16px);
}

.skill-name {
  margin: 0 0 4px;
  font-size: 0.96rem;
  font-weight: 700;
  color: #f8fafc;
}

.skill-desc {
  margin: 0 0 10px;
  font-size: 0.81rem;
  color: #94a3b8;
}

.skill-detail-box {
  background: rgba(15, 23, 42, 0.55);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-label {
  font-size: 0.74rem;
  font-weight: 700;
  color: #cbd5e1;
}

.detail-text {
  margin: 0;
  font-size: 0.81rem;
  color: #e2e8f0;
  line-height: 1.45;
}

.signal-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.signal-chip {
  font-size: 0.71rem;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(139, 92, 246, 0.14);
  color: #c4b5fd;
  border: 1px solid rgba(139, 92, 246, 0.28);
}

.examples-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 8px;
  margin-top: 2px;
}

.example-box {
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 0.77rem;
}

.example-box p {
  margin: 4px 0 0;
  line-height: 1.4;
}

.good-box {
  background: rgba(16, 185, 129, 0.1);
  border: 1px solid rgba(16, 185, 129, 0.25);
  color: #a7f3d0;
}

.anti-box {
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.25);
  color: #fecaca;
}

.example-title {
  font-weight: 700;
  font-size: 0.73rem;
}

.empty-state {
  text-align: center;
  padding: 40px 20px;
  color: #94a3b8;
}

.modal-footer {
  padding: 12px 24px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: rgba(15, 23, 42, 0.65);
}

.footer-hint {
  font-size: 0.77rem;
  color: #94a3b8;
}
</style>
