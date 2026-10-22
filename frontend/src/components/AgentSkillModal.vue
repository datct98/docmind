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
              <h3 class="modal-title">
                {{ showForm ? (editingSkillId ? '✏️ Chỉnh sửa Kỹ năng Chuyên gia AI' : '✨ Dạy Kỹ năng Mới cho AI (Chế độ Thông minh 1 Bước)') : 'Kho Kỹ năng Chuyên gia AI (Agent Skill Registry)' }}
              </h3>
              <span v-if="!showForm" class="badge badge-accent">{{ skills.length }} kỹ năng</span>
            </div>
            <p class="modal-subtitle">
              {{ showForm
                ? 'Chỉ cần gõ bằng lời tự nhiên như đang dặn nhân viên Sale — Hệ thống AI sẽ tự động phân tích tín hiệu kích hoạt, chiến thuật và câu mẫu chuẩn.'
                : 'Hệ thống plugin kỹ năng Tâm lý học khách hàng, Chiến thuật Chốt Sale & Nghệ thuật Giao tiếp tự động kích hoạt theo thời gian thực' }}
            </p>
          </div>
        </div>
        <div class="header-right-actions">
          <button v-if="showForm" class="btn btn-ghost btn-sm btn-back-list" @click="closeForm">
            ← Quay lại danh sách ({{ skills.length }})
          </button>
          <button class="btn-close" @click="handleClose">×</button>
        </div>
      </div>

      <!-- VIEW 1: DEDICATED FULL-VIEW CREATE / EDIT FORM (Never covered by list) -->
      <div v-if="showForm" class="skill-form-view">
        <div class="skill-form-scroll-body">
          <!-- Quick Sales Templates Banner (Only on Create or when desired) -->
          <div class="quick-templates-box">
            <div class="quick-templates-header">
              <span class="quick-templates-title">⚡ Mẫu Kỹ năng Chốt Sale Thực chiến (Bấm 1 chạm để điền mẫu ngay):</span>
              <span class="quick-templates-sub">Giúp Agent hiểu ngay mà không cần tự nghĩ cấu trúc</span>
            </div>
            <div class="quick-templates-grid">
              <button
                v-for="tpl in quickTemplates"
                :key="tpl.id"
                type="button"
                class="template-chip-card"
                @click="applyQuickTemplate(tpl)"
              >
                <span class="tpl-icon">{{ tpl.icon }}</span>
                <div class="tpl-content">
                  <strong>{{ tpl.title }}</strong>
                  <span>{{ tpl.subtitle }}</span>
                </div>
              </button>
            </div>
          </div>

          <!-- 1-Step Smart Natural Language Input -->
          <div class="smart-input-card">
            <div class="smart-input-header">
              <div>
                <label class="smart-label">
                  💬 Mô tả bằng lời tự nhiên cách bạn muốn AI xử lý tình huống <span class="required">*</span>
                </label>
                <p class="smart-helper">
                  Bạn chỉ cần viết tự nhiên vào đúng 1 ô này (ví dụ: <em>"Khi khách hỏi giá chung chung, đừng xả hết bảng giá, chỉ nêu 1 mức giá khởi điểm dễ tiếp cận kèm lợi ích cốt lõi rồi hỏi khách muốn ưu tiên nhu cầu nào"</em>). Hệ thống sẽ tự động kiểm tra trùng lặp để gộp hoặc cảnh báo nếu xung đột.
                </p>
              </div>
              <button
                type="button"
                class="btn btn-ai-parse btn-sm"
                :disabled="parsingSmart || (!formData.raw_instruction.trim() && !formData.tactical_instructions.trim())"
                @click="handleSmartParsePreview"
              >
                {{ parsingSmart ? '⏳ Đang phân tích...' : '✨ AI Tự Phân Tích & Điền Chi Tiết' }}
              </button>
            </div>

            <textarea
              v-model="formData.raw_instruction"
              rows="4"
              class="form-textarea smart-textarea"
              placeholder="Nhập lời dặn dò của bạn cho AI tại đây... (VD: Khi khách chê đắt hoặc đòi suy nghĩ thêm, tuyệt đối không tự ý hạ giá. Hãy đồng cảm LAER, neo giá trị khác biệt từ tài liệu của Page và mời khách trải nghiệm/tư vấn bước đầu không rủi ro vào Sáng hoặc Chiều.)"
            ></textarea>

            <!-- Quick Scope & Category Row -->
            <div class="smart-meta-row">
              <div class="form-group compact-group">
                <label class="form-label">Phạm vi áp dụng</label>
                <select v-model="formData.page_id" class="form-select">
                  <option :value="null">🌐 Toàn hệ thống (Mọi Fanpage / Kênh)</option>
                  <option v-for="p in pages" :key="p.id" :value="p.id">
                    📂 Chỉ áp dụng cho: {{ p.name }}
                  </option>
                </select>
              </div>

              <div class="form-group compact-group">
                <label class="form-label">Nhóm kỹ năng (AI tự nhận diện nếu để mặc định)</label>
                <select v-model="formData.category" class="form-select">
                  <option value="">🤖 Tự động nhận diện bằng AI (Mặc định)</option>
                  <option value="SALES_STRATEGY">🎯 Chiến thuật Tư vấn &amp; Chốt Sale (SALES_STRATEGY)</option>
                  <option value="PSYCHOLOGY">🧠 Tâm lý học khách hàng (PSYCHOLOGY)</option>
                  <option value="COMMUNICATION">💬 Nghệ thuật Giao tiếp &amp; Ngôn từ (COMMUNICATION)</option>
                </select>
              </div>
            </div>

            <div v-if="smartParseNotice" class="smart-parse-success">
              {{ smartParseNotice }}
            </div>
          </div>

          <!-- Collapsible Advanced / Detailed Fields -->
          <div class="advanced-section-card">
            <button
              type="button"
              class="advanced-toggle-btn"
              @click="showAdvancedFields = !showAdvancedFields"
            >
              <div class="advanced-toggle-left">
                <span class="adv-chevron">{{ showAdvancedFields ? '▼' : '▶' }}</span>
                <span>⚙️ Xem &amp; tinh chỉnh chi tiết nâng cao</span>
                <span class="adv-optional-badge">Không bắt buộc — AI tự sinh nếu bỏ trống</span>
              </div>
              <span class="adv-toggle-action">{{ showAdvancedFields ? 'Thu gọn' : 'Mở rộng 6 trường chi tiết' }}</span>
            </button>

            <div v-if="showAdvancedFields" class="advanced-fields-body">
              <div class="form-grid">
                <div class="form-group">
                  <label class="form-label">Tên kỹ năng hiển thị</label>
                  <input
                    v-model="formData.name"
                    type="text"
                    class="form-input"
                    placeholder="Bỏ trống để AI tự đặt tên ngắn gọn..."
                  />
                </div>

                <div class="form-group">
                  <label class="form-label">Độ ưu tiên kích hoạt (1 - 100)</label>
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
                <label class="form-label">Mô tả tóm tắt mục tiêu kỹ năng</label>
                <input
                  v-model="formData.description"
                  type="text"
                  class="form-input"
                  placeholder="Mô tả ngắn gọn khi nào và tại sao Agent cần kích hoạt kỹ năng này..."
                />
              </div>

              <div class="form-group">
                <label class="form-label">Từ khóa / Tín hiệu kích hoạt tự động (phân tách bằng dấu phẩy)</label>
                <input
                  v-model="formData.trigger_signals"
                  type="text"
                  class="form-input"
                  placeholder="VD: giá, báo giá, bao nhiêu tiền, đắt quá, suy nghĩ thêm..."
                />
              </div>

              <div class="form-group">
                <label class="form-label">Chỉ dẫn Chiến thuật chuẩn hóa cho AI (Tactical Instructions)</label>
                <textarea
                  v-model="formData.tactical_instructions"
                  rows="3"
                  class="form-textarea"
                  placeholder="Chỉ dẫn chi tiết bơm vào System Prompt khi kỹ năng kích hoạt..."
                ></textarea>
              </div>

              <div class="form-grid">
                <div class="form-group">
                  <label class="form-label">✅ Câu trả lời mẫu chuẩn mực (Good Example)</label>
                  <textarea
                    v-model="formData.good_example"
                    rows="3"
                    class="form-textarea"
                    placeholder="Ví dụ câu trả lời mẫu gồm 1 lợi ích + 1 câu hỏi khống chế A/B..."
                  ></textarea>
                </div>

                <div class="form-group">
                  <label class="form-label">🚫 Điều cấm kỵ tuyệt đối tránh (Anti-Pattern)</label>
                  <textarea
                    v-model="formData.anti_pattern"
                    rows="3"
                    class="form-textarea"
                    placeholder="Ví dụ: Liệt kê 4 dịch vụ + Combo một lèo khiến khách ngợp..."
                  ></textarea>
                </div>
              </div>
            </div>
          </div>

          <div v-if="formError" class="form-error">{{ formError }}</div>
        </div>

        <!-- Sticky Form Footer (Always visible, never clipped) -->
        <div class="form-sticky-footer">
          <div class="form-footer-hint">
            💡 <strong>Mẹo:</strong> Bạn có thể bấm <strong>"{{ editingSkillId ? 'Lưu cập nhật' : 'Lưu Kỹ năng Ngay' }}"</strong> ngay sau khi gõ ô mô tả tự nhiên mà không cần mở phần nâng cao.
          </div>
          <div class="form-actions">
            <button type="button" class="btn btn-ghost btn-sm" @click="closeForm">Hủy &amp; Quay lại</button>
            <button type="button" class="btn btn-primary btn-save-main" :disabled="saving" @click="saveSkill">
              {{ saving ? '⏳ Đang phân tích & lưu...' : (editingSkillId ? '💾 Lưu cập nhật kỹ năng' : '💾 Lưu Kỹ năng Ngay') }}
            </button>
          </div>
        </div>
      </div>

      <!-- VIEW 2: SKILLS LIST VIEW -->
      <template v-else>
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

        <!-- Skills List Body -->
        <div class="skills-list-body">
          <!-- Governance Auto-Merge / Override Notification Banner -->
          <div v-if="governanceNotice" class="governance-notice-banner" :class="governanceNotice.type">
            <div class="gov-notice-content">
              <span class="gov-notice-icon">{{ governanceNotice.icon }}</span>
              <div>
                <strong>{{ governanceNotice.title }}</strong>
                <p>{{ governanceNotice.message }}</p>
              </div>
            </div>
            <button type="button" class="btn-close-gov" @click="governanceNotice = null">×</button>
          </div>

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
                  <span class="telemetry-badge" title="Số lượt kỹ năng được kích hoạt trong hội thoại thực tế">
                    📊 Đã kích hoạt: {{ skill.applied_count || 0 }} lượt
                  </span>
                  <span class="telemetry-badge telemetry-conversion" title="Số ca khách để lại SĐT / Chốt lịch khi kỹ năng này tham gia">
                    🏆 Hỗ trợ chốt SĐT: {{ skill.conversion_count || 0 }} ca
                  </span>
                  <span class="telemetry-badge telemetry-confidence" title="Độ tín nhiệm tự tiến hóa từ chuyển đổi thực tế">
                    🛡️ Độ tín nhiệm: {{ Math.round((skill.confidence_score ?? 0.90) * 100) }}%
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
            💡 <strong>Cơ chế hoạt động:</strong> Hệ thống tự động <strong>Gộp kỹ năng tương đồng (Auto-Merge)</strong> và cảnh báo khi phát hiện <strong>Xung đột chiến thuật</strong> để đảm bảo AI luôn nhất quán.
          </span>
          <button class="btn btn-ghost btn-sm" @click="handleClose">Đóng</button>
        </div>
      </template>

      <!-- MODAL XÁC NHẬN XUNG ĐỘT CHIẾN THUẬT (CONFLICT RESOLUTION MODAL) -->
      <div v-if="conflictModalData" class="conflict-modal-backdrop" @click.self="skipConflictCandidate">
        <div class="conflict-modal-card">
          <div class="conflict-modal-header">
            <div class="conflict-header-title">
              <span class="conflict-alert-icon">⚠️</span>
              <div>
                <h4>Phát hiện Xung đột Chiến thuật với Kỹ năng Hiện có!</h4>
                <p>Hệ thống phát hiện chỉ dẫn mới đối lập với một kỹ năng đang kích hoạt trên phạm vi này.</p>
              </div>
            </div>
            <button type="button" class="btn-close" @click="skipConflictCandidate">×</button>
          </div>

          <div class="conflict-modal-body">
            <div class="conflict-reason-box">
              <strong>🔍 Chi tiết điểm mâu thuẫn:</strong>
              <p>{{ conflictModalData.conflict_reason }}</p>
            </div>

            <div v-if="conflictModalData.recommended_action" class="conflict-recommend-box">
              <strong>💡 Đánh giá &amp; Khuyến nghị từ AI:</strong>
              <p>{{ conflictModalData.recommended_action }}</p>
            </div>

            <div class="conflict-comparison-grid">
              <!-- Cột 1: Kỹ năng hiện có -->
              <div class="conflict-col existing-col">
                <div class="conflict-col-badge">🛡️ KỸ NĂNG HIỆN CÓ ĐANG BẬT</div>
                <h5>{{ conflictModalData.conflicting_skill?.name }}</h5>
                <div class="conflict-meta-tags">
                  <span class="scope-tag">
                    {{ conflictModalData.conflicting_skill?.page_id ? `📂 ${getPageName(conflictModalData.conflicting_skill.page_id)}` : '🌐 Toàn hệ thống' }}
                  </span>
                  <span class="prio-tag">⚡ Ưu tiên {{ conflictModalData.conflicting_skill?.priority }}</span>
                </div>
                <div class="conflict-field">
                  <span class="cf-label">📡 Tín hiệu kích hoạt:</span>
                  <p>{{ conflictModalData.conflicting_skill?.trigger_signals }}</p>
                </div>
                <div class="conflict-field">
                  <span class="cf-label">🎯 Chỉ dẫn hiện tại:</span>
                  <p class="cf-pre">{{ conflictModalData.conflicting_skill?.tactical_instructions }}</p>
                </div>
              </div>

              <!-- Cột 2: Kỹ năng mới đang nạp -->
              <div class="conflict-col candidate-col">
                <div class="conflict-col-badge candidate-badge">✨ KỸ NĂNG MỚI BẠN VỪA NHẬP</div>
                <h5>{{ conflictModalData.name }}</h5>
                <div class="conflict-meta-tags">
                  <span class="scope-tag">
                    {{ conflictModalData.page_id ? `📂 ${getPageName(conflictModalData.page_id)}` : '🌐 Toàn hệ thống' }}
                  </span>
                  <span class="prio-tag">⚡ Ưu tiên {{ conflictModalData.priority }}</span>
                </div>
                <div class="conflict-field">
                  <span class="cf-label">📡 Tín hiệu kích hoạt:</span>
                  <p>{{ conflictModalData.trigger_signals }}</p>
                </div>
                <div class="conflict-field">
                  <span class="cf-label">🎯 Chỉ dẫn mới:</span>
                  <p class="cf-pre">{{ conflictModalData.tactical_instructions }}</p>
                </div>
              </div>
            </div>
          </div>

          <div class="conflict-modal-footer">
            <button type="button" class="btn btn-ghost btn-sm" :disabled="saving" @click="skipConflictCandidate">
              ⚪ Bỏ qua (Giữ kỹ năng cũ)
            </button>
            <div class="conflict-right-btns">
              <button type="button" class="btn btn-edit-conflict btn-sm" :disabled="saving" @click="editConflictCandidate">
                ✏️ Chỉnh sửa lại
              </button>
              <button type="button" class="btn btn-override-conflict btn-sm" :disabled="saving" @click="confirmConflictOverride">
                {{ saving ? '⏳ Đang ghi đè...' : '🔴 Xác nhận Ghi đè bằng Kỹ năng mới' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api.js'

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
const showAdvancedFields = ref(false)
const editingSkillId = ref(null)
const saving = ref(false)
const parsingSmart = ref(false)
const formError = ref('')
const smartParseNotice = ref('')
const governanceNotice = ref(null)
const conflictModalData = ref(null)

const quickTemplates = [
  {
    id: 'anti_dumping_3_step',
    icon: '🛡️',
    title: 'Chống "Xả sạch thông tin" khi hỏi Giá (3 Nấc)',
    subtitle: 'Neo 1 mức giá khởi điểm + 1 lợi ích cốt lõi + Hỏi A/B (<40 từ)',
    category: 'SALES_STRATEGY',
    raw: 'Khi khách mới hỏi "giá", "báo giá" chung chung mà chưa nói rõ nhu cầu: Tuyệt đối không xả sạch bảng giá nhiều gói. Chỉ nêu 1 mức giá khởi điểm dễ tiếp cận nhất trong tài liệu của Page kèm 1 quyền lợi miễn phí/trọn gói và đặt 1 câu hỏi lựa chọn A/B dưới 40 từ để hỏi nhu cầu ưu tiên.'
  },
  {
    id: 'appointment_hook_ab',
    icon: '🎯',
    title: 'Cam kết Vi mô (Micro-Commitment) & Chốt Lịch A/B',
    subtitle: '1 Tin nhắn = 1 Lợi ích + 1 Câu hỏi chốt Sáng hay Chiều',
    category: 'SALES_STRATEGY',
    raw: 'Khi khách đã quan tâm dịch vụ/sản phẩm, tuyệt đối không hỏi đóng hoặc xin SĐT quá sớm. Hãy dùng 1 quyền lợi trải nghiệm thực tế làm móc câu và đặt câu hỏi lựa chọn A/B nhẹ nhàng: Anh/Chị tiện ghé hoặc trao đổi vào khung giờ Sáng hay Chiều ạ?'
  },
  {
    id: 'price_objection_value',
    icon: '💎',
    title: 'Đàm phán LAER khi khách chê đắt / "Suy nghĩ thêm"',
    subtitle: 'Đồng cảm + Neo giá trị khác biệt + Đề xuất bước thử 0 rủi ro',
    category: 'SALES_STRATEGY',
    raw: 'Khi khách chê giá cao, so sánh giá rẻ hoặc nói để suy nghĩ thêm: Tuyệt đối không tự ý hạ giá. Hãy đồng cảm ngắn gọn, phân hóa rõ chất lượng thực tế trong tài liệu của Page so với dịch vụ đại trà và mời khách trải nghiệm/tư vấn bước đầu 0 rủi ro vào Sáng hoặc Chiều.'
  },
  {
    id: 'empathy_fear_of_pain',
    icon: '🤝',
    title: 'Thấu cảm Gắn nhãn (FBI Voss) & Xóa bỏ Rào cản Sợ rủi ro',
    subtitle: 'Gọi tên nỗi lo + Cam kết minh bạch trọn gói không chèo kéo',
    category: 'PSYCHOLOGY',
    raw: 'Khi khách lo lắng về hiệu quả, sợ đau hoặc sợ phát sinh chi phí/bị chèo kéo: Hãy đồng cảm ngay câu đầu, khẳng định cam kết minh bạch trọn gói từ tài liệu của Page và mời khách kiểm tra thực tế trước khi quyết định.'
  }
]

const originalTacticalInstructions = ref('')

const formData = ref({
  raw_instruction: '',
  name: '',
  code: '',
  category: '',
  page_id: props.activePageId || null,
  priority: 90,
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
    skills.value = await api.getSkills()
    emit('skills-updated', skills.value)
  } catch (err) {
    console.error('Lỗi tải danh sách Kỹ năng Agent:', err)
    skills.value = []
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

const applyQuickTemplate = async (tpl) => {
  formData.value.raw_instruction = tpl.raw
  formData.value.category = tpl.category
  formData.value.name = ''
  formData.value.description = ''
  formData.value.trigger_signals = ''
  formData.value.tactical_instructions = ''
  formData.value.good_example = ''
  formData.value.anti_pattern = ''
  formError.value = ''
  await handleSmartParsePreview()
}

const handleSmartParsePreview = async () => {
  const rawText = (formData.value.raw_instruction || formData.value.tactical_instructions || '').trim()
  if (!rawText) {
    formError.value = 'Vui lòng nhập mô tả bằng lời tự nhiên trước khi phân tích.'
    return
  }
  parsingSmart.value = true
  formError.value = ''
  smartParseNotice.value = ''
  try {
    const reqPayload = {
      raw_instruction: rawText,
      category: formData.value.category,
      page_id: formData.value.page_id,
      priority: formData.value.priority
    }
    const parsed = await api.smartParseSkill(reqPayload)

    formData.value.name = parsed.name || formData.value.name
    formData.value.code = parsed.code || formData.value.code
    formData.value.category = parsed.category || formData.value.category
    formData.value.description = parsed.description || formData.value.description
    formData.value.trigger_signals = parsed.trigger_signals || formData.value.trigger_signals
    formData.value.tactical_instructions = parsed.tactical_instructions || formData.value.tactical_instructions
    formData.value.good_example = parsed.good_example || formData.value.good_example
    formData.value.anti_pattern = parsed.anti_pattern || formData.value.anti_pattern
    formData.value.priority = parsed.priority || formData.value.priority || 90
    showAdvancedFields.value = true
    smartParseNotice.value = '✅ Đã tự động phân tích thành công! Bạn có thể bấm "Lưu Kỹ năng Ngay" hoặc tinh chỉnh thêm bên dưới.'
  } catch (err) {
    formError.value = err.message || 'Lỗi khi phân tích kỹ năng tự động.'
  } finally {
    parsingSmart.value = false
  }
}

const openCreateForm = () => {
  editingSkillId.value = null
  originalTacticalInstructions.value = ''
  formError.value = ''
  smartParseNotice.value = ''
  showAdvancedFields.value = false
  formData.value = {
    raw_instruction: '',
    name: '',
    code: '',
    category: selectedCategory.value || '',
    page_id: (selectedPageFilter.value !== 'ALL' && selectedPageFilter.value !== 'GLOBAL')
      ? selectedPageFilter.value
      : (props.activePageId || null),
    priority: 90,
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
  originalTacticalInstructions.value = skill.tactical_instructions || ''
  formError.value = ''
  smartParseNotice.value = ''
  showAdvancedFields.value = true
  formData.value = {
    raw_instruction: skill.tactical_instructions || skill.description || '',
    name: skill.name || '',
    code: skill.code || '',
    category: skill.category || 'SALES_STRATEGY',
    page_id: skill.page_id || null,
    priority: skill.priority || 85,
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
  originalTacticalInstructions.value = ''
  formError.value = ''
  smartParseNotice.value = ''
  conflictModalData.value = null
}

const saveSkill = async () => {
  const hasRaw = (formData.value.raw_instruction || '').trim().length > 0
  const hasName = (formData.value.name || '').trim().length > 0
  const hasTactical = (formData.value.tactical_instructions || '').trim().length > 0

  if (!hasRaw && !hasName && !hasTactical) {
    formError.value = 'Vui lòng mô tả kỹ năng bằng lời tự nhiên (hoặc chọn 1 mẫu kịch bản phía trên).'
    return
  }

  saving.value = true
  formError.value = ''
  try {
    const payload = { ...formData.value, resolution_strategy: 'AUTO' }
    if (editingSkillId.value) {
      if (hasRaw && (!hasTactical || formData.value.tactical_instructions === originalTacticalInstructions.value)) {
        payload.tactical_instructions = formData.value.raw_instruction.trim()
      }
      await api.updateSkill(editingSkillId.value, payload)
      await loadSkills()
      closeForm()
    } else {
      const res = await api.createSkill(payload)
      if (res && res.conflict_detected) {
        conflictModalData.value = res
        return
      }
      if (res && res.governance_action === 'AUTO_MERGED') {
        governanceNotice.value = {
          type: 'merged',
          icon: '🔄',
          title: 'Đã tự động gộp kỹ năng tương đồng (Auto-Merged)',
          message: res.governance_message || `Hệ thống đã tự động gộp tín hiệu và chỉ dẫn bổ sung vào kỹ năng: ${res.name}`
        }
      } else if (res && res.governance_action === 'OVERRIDDEN') {
        governanceNotice.value = {
          type: 'overridden',
          icon: '⚡',
          title: 'Đã ghi đè kỹ năng xung đột',
          message: res.governance_message || `Đã áp dụng kỹ năng: ${res.name}`
        }
      }
      await loadSkills()
      closeForm()
    }
  } catch (err) {
    formError.value = err.message || 'Không thể lưu kỹ năng.'
  } finally {
    saving.value = false
  }
}

const confirmConflictOverride = async () => {
  if (!conflictModalData.value) return
  saving.value = true
  formError.value = ''
  try {
    const payload = {
      ...formData.value,
      name: conflictModalData.value.name || formData.value.name,
      category: conflictModalData.value.category || formData.value.category,
      trigger_signals: conflictModalData.value.trigger_signals || formData.value.trigger_signals,
      tactical_instructions: conflictModalData.value.tactical_instructions || formData.value.tactical_instructions,
      good_example: conflictModalData.value.good_example || formData.value.good_example,
      anti_pattern: conflictModalData.value.anti_pattern || formData.value.anti_pattern,
      resolution_strategy: 'OVERRIDE',
      conflict_skill_id: conflictModalData.value.conflicting_skill?.id || null
    }
    const res = await api.createSkill(payload)
    governanceNotice.value = {
      type: 'overridden',
      icon: '⚡',
      title: 'Đã xác nhận Ghi đè kỹ năng thành công',
      message: res?.governance_message || `Đã áp dụng kỹ năng mới "${res?.name || payload.name}".`
    }
    conflictModalData.value = null
    await loadSkills()
    closeForm()
  } catch (err) {
    formError.value = err.message || 'Lỗi khi ghi đè kỹ năng.'
    conflictModalData.value = null
  } finally {
    saving.value = false
  }
}

const editConflictCandidate = () => {
  if (conflictModalData.value) {
    formData.value.name = conflictModalData.value.name || formData.value.name
    formData.value.category = conflictModalData.value.category || formData.value.category
    formData.value.description = conflictModalData.value.description || formData.value.description
    formData.value.trigger_signals = conflictModalData.value.trigger_signals || formData.value.trigger_signals
    formData.value.tactical_instructions = conflictModalData.value.tactical_instructions || formData.value.tactical_instructions
    formData.value.good_example = conflictModalData.value.good_example || formData.value.good_example
    formData.value.anti_pattern = conflictModalData.value.anti_pattern || formData.value.anti_pattern
  }
  conflictModalData.value = null
  showAdvancedFields.value = true
  smartParseNotice.value = '✏️ Bạn có thể điều chỉnh lại Tín hiệu kích hoạt hoặc Chỉ dẫn chiến thuật bên dưới rồi bấm Lưu.'
}

const skipConflictCandidate = () => {
  conflictModalData.value = null
  closeForm()
}

const toggleSkillActive = async (skill) => {
  const newActive = !skill.active
  try {
    await api.updateSkill(skill.id, { active: newActive })
    skill.active = newActive
    emit('skills-updated', skills.value)
  } catch (err) {
    console.error('Lỗi bật/tắt kỹ năng:', err)
  }
}

const handleDeleteSkill = async (skill) => {
  if (!confirm(`Bạn có chắc chắn muốn xóa kỹ năng "${skill.name}"?`)) return
  try {
    await api.deleteSkill(skill.id)
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
  background: rgba(15, 23, 42, 0.45);
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
  max-height: 92vh;
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 24px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.18), 0 4px 16px rgba(15, 23, 42, 0.06);
}

.modal-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 24px;
  border-bottom: 1px solid #DDE3EA;
  background: #F8FAFD;
}

.modal-title-group {
  display: flex;
  align-items: center;
  gap: 14px;
}

.header-right-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.btn-back-list {
  border: 1px solid #DDE3EA;
  background: #FFFFFF;
  color: #1F1F1F;
  padding: 7px 14px;
  border-radius: 999px;
  font-weight: 600;
  font-size: 0.82rem;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-back-list:hover {
  background: #EDF2FA;
  border-color: #A8C7FA;
  color: #0B57D0;
}

.icon-skill-box {
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  border-radius: 14px;
  background: linear-gradient(135deg, #0B57D0, #1A73E8);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(11, 87, 208, 0.25);
}

.title-with-badge {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: 1.12rem;
  font-weight: 700;
  color: #1F1F1F;
}

.modal-subtitle {
  margin: 4px 0 0;
  font-size: 0.81rem;
  color: #444746;
}

.btn-close {
  background: transparent;
  border: none;
  color: #747775;
  font-size: 1.6rem;
  cursor: pointer;
  line-height: 1;
  padding: 4px 8px;
  border-radius: 999px;
}

.btn-close:hover {
  background: #EDF2FA;
  color: #1F1F1F;
}

/* DEDICATED FULL-VIEW SKILL FORM */
.skill-form-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #F0F4F9;
}

.skill-form-scroll-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.quick-templates-box {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 16px;
  padding: 14px 16px;
}

.quick-templates-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.quick-templates-title {
  font-size: 0.82rem;
  font-weight: 700;
  color: #0B57D0;
}

.quick-templates-sub {
  font-size: 0.75rem;
  color: #444746;
}

.quick-templates-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}

.template-chip-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  text-align: left;
  padding: 10px 12px;
  border-radius: 12px;
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  color: #1F1F1F;
  cursor: pointer;
  transition: all 0.16s ease;
}

.template-chip-card:hover {
  border-color: #A8C7FA;
  background: #E8F0FE;
  transform: translateY(-1px);
}

.tpl-icon {
  font-size: 1.2rem;
  line-height: 1.2;
}

.tpl-content {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.tpl-content strong {
  font-size: 0.8rem;
  color: #1F1F1F;
}

.tpl-content span {
  font-size: 0.73rem;
  color: #444746;
  line-height: 1.35;
}

.smart-input-card {
  background: #FFFFFF;
  border: 1px solid #A8C7FA;
  border-radius: 16px;
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  box-shadow: 0 4px 16px rgba(11, 87, 208, 0.06);
}

.smart-input-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}

.smart-label {
  display: block;
  font-size: 0.92rem;
  font-weight: 700;
  color: #1F1F1F;
  margin-bottom: 4px;
}

.smart-helper {
  margin: 0;
  font-size: 0.79rem;
  color: #444746;
  line-height: 1.45;
  max-width: 680px;
}

.btn-ai-parse {
  background: #0B57D0;
  color: #fff;
  border: none;
  padding: 8px 16px;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  box-shadow: 0 2px 8px rgba(11, 87, 208, 0.25);
}

.btn-ai-parse:hover:not(:disabled) {
  background: #0842A0;
}

.btn-ai-parse:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.smart-textarea {
  font-size: 0.88rem !important;
  line-height: 1.5;
  border-color: #DDE3EA !important;
  background: #F8FAFD !important;
  color: #1F1F1F !important;
}

.smart-textarea:focus {
  outline: none;
  background: #FFFFFF !important;
  border-color: #0B57D0 !important;
  box-shadow: 0 0 0 3px rgba(11, 87, 208, 0.14);
}

.smart-meta-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}

.compact-group {
  margin-bottom: 0 !important;
}

.smart-parse-success {
  padding: 9px 12px;
  border-radius: 10px;
  background: #E6F4EA;
  border: 1px solid #A8DAB5;
  color: #137333;
  font-size: 0.8rem;
  font-weight: 600;
}

.advanced-section-card {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 14px;
  overflow: hidden;
}

.advanced-toggle-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #F8FAFD;
  border: none;
  color: #1F1F1F;
  font-size: 0.83rem;
  font-weight: 600;
  cursor: pointer;
}

.advanced-toggle-btn:hover {
  background: #EDF2FA;
}

.advanced-toggle-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.adv-chevron {
  font-size: 0.75rem;
  color: #0B57D0;
}

.adv-optional-badge {
  font-size: 0.72rem;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 999px;
  background: #E8F0FE;
  color: #0B57D0;
}

.adv-toggle-action {
  font-size: 0.76rem;
  color: #444746;
}

.advanced-fields-body {
  padding: 16px;
  border-top: 1px solid #DDE3EA;
  background: #FFFFFF;
}

.form-sticky-footer {
  flex-shrink: 0;
  padding: 14px 24px;
  background: #FFFFFF;
  border-top: 1px solid #DDE3EA;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}

.form-footer-hint {
  font-size: 0.78rem;
  color: #444746;
}

.btn-save-main {
  padding: 9px 18px;
  font-size: 0.86rem;
  font-weight: 600;
}

.toolbar-section {
  flex-shrink: 0;
  padding: 14px 24px;
  border-bottom: 1px solid #DDE3EA;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: #FFFFFF;
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
  color: #747775;
}

.search-input {
  width: 100%;
  padding: 9px 32px 9px 36px;
  border-radius: 999px;
  border: 1px solid #DDE3EA;
  background: #F8FAFD;
  color: #1F1F1F;
  font-size: 0.86rem;
  outline: none;
  transition: border-color 0.2s, background-color 0.2s, box-shadow 0.2s;
}

.search-input:focus {
  background: #FFFFFF;
  border-color: #0B57D0;
  box-shadow: 0 0 0 3px rgba(11, 87, 208, 0.12);
}

.btn-clear-search {
  position: absolute;
  right: 10px;
  background: transparent;
  border: none;
  color: #747775;
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
  color: #747775;
  font-weight: 600;
  margin-right: 4px;
}

.pill-filter {
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 0.76rem;
  font-weight: 500;
  border: 1px solid #DDE3EA;
  background: #F8FAFD;
  color: #444746;
  cursor: pointer;
  transition: all 0.15s ease;
}

.pill-filter:hover {
  border-color: #A8C7FA;
  background: #EDF2FA;
  color: #1F1F1F;
}

.pill-filter.active {
  background: #D3E3FD;
  border-color: #A8C7FA;
  color: #041E49;
  font-weight: 600;
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
  color: #444746;
}

.required {
  color: #C5221F;
}

.form-input,
.form-select,
.form-textarea {
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid #DDE3EA;
  background: #F8FAFD;
  color: #1F1F1F;
  font-size: 0.83rem;
  font-family: inherit;
  outline: none;
}

.form-input:focus,
.form-select:focus,
.form-textarea:focus {
  background: #FFFFFF;
  border-color: #0B57D0;
  box-shadow: 0 0 0 3px rgba(11, 87, 208, 0.12);
}

.form-error {
  color: #C5221F;
  background: #FCE8E6;
  border: 1px solid #F6AEA9;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 0.82rem;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.skills-list-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 24px;
  background: #F0F4F9;
}

.skills-grid {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.skill-card {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 16px;
  padding: 14px 16px;
  transition: all 0.18s ease;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}

.skill-card:hover {
  border-color: #A8C7FA;
  box-shadow: 0 4px 12px rgba(11, 87, 208, 0.08);
}

.skill-card.skill-inactive {
  opacity: 0.65;
  background: #F8FAFD;
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
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 0.72rem;
  font-weight: 700;
}

.badge-psych {
  background: #E8DDF8;
  color: #4A148C;
  border: 1px solid #D0BCFF;
}

.badge-sales {
  background: #E6F4EA;
  color: #137333;
  border: 1px solid #A8DAB5;
}

.badge-comm {
  background: #E8F0FE;
  color: #0B57D0;
  border: 1px solid #A8C7FA;
}

.scope-badge {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 0.71rem;
  font-weight: 600;
}

.scope-global {
  background: #F0F4F9;
  color: #444746;
  border: 1px solid #DDE3EA;
}

.scope-page {
  background: #FEF7E0;
  color: #B06000;
  border: 1px solid #FDE293;
}

.priority-badge {
  font-size: 0.71rem;
  font-weight: 600;
  color: #B06000;
  background: #FEF7E0;
  border: 1px solid #FDE293;
  padding: 2px 8px;
  border-radius: 999px;
}

.code-badge {
  font-family: var(--font-mono);
  font-size: 0.7rem;
  color: #444746;
  background: #F0F4F9;
  border: 1px solid #DDE3EA;
  padding: 2px 6px;
  border-radius: 6px;
}

.skill-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-icon {
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  border-radius: 8px;
  padding: 4px 8px;
  cursor: pointer;
  font-size: 0.8rem;
  color: #444746;
}

.btn-icon:hover {
  background: #EDF2FA;
  border-color: #A8C7FA;
  color: #0B57D0;
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
  background-color: #C4CDD5;
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
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
}

.switch-toggle input:checked + .switch-slider {
  background-color: #0B57D0;
}

.switch-toggle input:checked + .switch-slider:before {
  transform: translateX(16px);
}

.skill-name {
  margin: 0 0 4px;
  font-size: 0.96rem;
  font-weight: 700;
  color: #1F1F1F;
}

.skill-desc {
  margin: 0 0 10px;
  font-size: 0.81rem;
  color: #444746;
}

.skill-detail-box {
  background: #F8FAFD;
  border: 1px solid #E1E6EC;
  border-radius: 12px;
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
  color: #0B57D0;
}

.detail-text {
  margin: 0;
  font-size: 0.81rem;
  color: #1F1F1F;
  line-height: 1.45;
}

.signal-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.signal-chip {
  font-size: 0.71rem;
  font-weight: 500;
  padding: 2px 10px;
  border-radius: 999px;
  background: #E8F0FE;
  color: #041E49;
  border: 1px solid #D3E3FD;
}

.examples-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 8px;
  margin-top: 2px;
}

.example-box {
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 0.77rem;
}

.example-box p {
  margin: 4px 0 0;
  line-height: 1.4;
}

.good-box {
  background: #E6F4EA;
  border: 1px solid #A8DAB5;
  color: #0D5023;
}

.anti-box {
  background: #FCE8E6;
  border: 1px solid #F6AEA9;
  color: #8C1D18;
}

.example-title {
  font-weight: 700;
  font-size: 0.73rem;
}

.empty-state {
  text-align: center;
  padding: 40px 20px;
  color: #444746;
}

.modal-footer {
  flex-shrink: 0;
  padding: 12px 24px;
  border-top: 1px solid #DDE3EA;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #F8FAFD;
}

.footer-hint {
  font-size: 0.77rem;
  color: #444746;
}

.telemetry-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 0.7rem;
  font-weight: 600;
  background: #E8F0FE;
  color: #0B57D0;
  border: 1px solid #D3E3FD;
}

.telemetry-conversion {
  background: #E6F4EA;
  color: #137333;
  border-color: #A8DAB5;
}

.telemetry-confidence {
  background: #E8DDF8;
  color: #4A148C;
  border-color: #D0BCFF;
}

/* Governance Notice Banner */
.governance-notice-banner {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  margin-bottom: 14px;
  border-radius: 12px;
  background: #EFF6FF;
  border: 1px solid #93C5FD;
  color: #1E3A8A;
}

.governance-notice-banner.overridden {
  background: #FEF3C7;
  border-color: #F59E0B;
  color: #78350F;
}

.gov-notice-content {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.gov-notice-icon {
  font-size: 1.25rem;
  line-height: 1.2;
}

.gov-notice-content strong {
  display: block;
  font-size: 0.88rem;
  margin-bottom: 2px;
}

.gov-notice-content p {
  margin: 0;
  font-size: 0.82rem;
  line-height: 1.4;
}

.btn-close-gov {
  background: transparent;
  border: none;
  font-size: 1.2rem;
  cursor: pointer;
  color: inherit;
  opacity: 0.7;
}

/* Conflict Resolution Confirmation Modal */
.conflict-modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.65);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1400;
  padding: 20px;
}

.conflict-modal-card {
  width: 100%;
  max-width: 860px;
  max-height: 88vh;
  background: #FFFFFF;
  border: 2px solid #F59E0B;
  border-radius: 20px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 24px 48px rgba(15, 23, 42, 0.28);
}

.conflict-modal-header {
  padding: 16px 22px;
  background: #FFFBEB;
  border-bottom: 1px solid #FDE68A;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.conflict-header-title {
  display: flex;
  align-items: center;
  gap: 12px;
}

.conflict-alert-icon {
  font-size: 1.6rem;
}

.conflict-header-title h4 {
  margin: 0;
  font-size: 1.02rem;
  color: #92400E;
  font-weight: 700;
}

.conflict-header-title p {
  margin: 2px 0 0;
  font-size: 0.82rem;
  color: #B45309;
}

.conflict-modal-body {
  padding: 18px 22px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.conflict-reason-box {
  padding: 12px 14px;
  border-radius: 10px;
  background: #FEF2F2;
  border: 1px solid #FECACA;
  color: #991B1B;
  font-size: 0.85rem;
}

.conflict-reason-box p {
  margin: 4px 0 0;
  line-height: 1.45;
}

.conflict-recommend-box {
  padding: 12px 14px;
  border-radius: 10px;
  background: #EFF6FF;
  border: 1px solid #BFDBFE;
  color: #1E40AF;
  font-size: 0.84rem;
}

.conflict-recommend-box p {
  margin: 4px 0 0;
  line-height: 1.45;
}

.conflict-comparison-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 14px;
}

.conflict-col {
  padding: 14px;
  border-radius: 12px;
  border: 1px solid #E2E8F0;
  background: #F8FAFC;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.conflict-col.candidate-col {
  background: #F0FDF4;
  border-color: #BBF7D0;
}

.conflict-col-badge {
  display: inline-block;
  align-self: flex-start;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 0.7rem;
  font-weight: 700;
  background: #E2E8F0;
  color: #334155;
}

.conflict-col-badge.candidate-badge {
  background: #DCFCE7;
  color: #166534;
}

.conflict-col h5 {
  margin: 0;
  font-size: 0.92rem;
  color: #0F172A;
}

.conflict-meta-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 0.74rem;
  color: #475569;
}

.conflict-field {
  font-size: 0.8rem;
}

.cf-label {
  font-weight: 600;
  color: #334155;
  display: block;
  margin-bottom: 2px;
}

.conflict-field p {
  margin: 0;
  color: #1E293B;
  line-height: 1.4;
}

.cf-pre {
  white-space: pre-wrap;
}

.conflict-modal-footer {
  padding: 14px 22px;
  background: #F8FAFC;
  border-top: 1px solid #E2E8F0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.conflict-right-btns {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-edit-conflict {
  background: #EFF6FF;
  color: #1D4ED8;
  border: 1px solid #93C5FD;
  font-weight: 600;
  padding: 8px 14px;
  border-radius: 10px;
  cursor: pointer;
}

.btn-override-conflict {
  background: #DC2626;
  color: #FFFFFF;
  border: 1px solid #B91C1C;
  font-weight: 600;
  padding: 8px 16px;
  border-radius: 10px;
  cursor: pointer;
}

.btn-override-conflict:hover {
  background: #B91C1C;
}

@media (max-width: 768px) {
  .modal-backdrop {
    padding: 8px;
  }

  .skill-modal {
    max-height: calc(100dvh - 16px);
    border-radius: 18px;
  }

  .modal-header,
  .toolbar-section,
  .skills-list-body,
  .skill-form-scroll-body,
  .form-sticky-footer,
  .modal-footer {
    padding: 12px 14px;
    flex-wrap: wrap;
  }

  .search-action-row {
    flex-wrap: wrap;
  }
}
</style>
