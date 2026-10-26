<template>
  <div v-if="isOpen" class="modal-backdrop" @click.self="$emit('close')">
    <div class="fb-sim-modal">
      <!-- Top Bar -->
      <div class="fb-sim-header">
        <div class="fb-sim-title-row">
          <div class="fb-icon-badge">💬</div>
          <div>
            <h3 class="fb-sim-title">
              Giả lập Bot Trực Tin Nhắn Fanpage FB
              <span class="api-pill">POST /api/bot/fanpage/message</span>
            </h3>
            <p class="fb-sim-subtitle">
              Prompt riêng · Không trích dẫn nguồn · Không Q&A nội bộ · Nhịp tư vấn tự nhiên · Tự động đẩy ca khó cho Người xử lý
            </p>
          </div>
        </div>
        <button class="btn-close-modal" @click="$emit('close')">×</button>
      </div>

      <!-- Mobile 2-Tab Switcher (Visible on <= 900px) -->
      <div class="fb-sim-mobile-tabs">
        <button
          type="button"
          class="fb-sim-mobile-tab"
          :class="{ active: mobileSimTab === 'chat' }"
          @click="mobileSimTab = 'chat'"
        >
          <span>💬 Khung Chat Test</span>
          <span v-if="chatItems.length > 0" class="sim-m-badge">{{ chatItems.length }}</span>
        </button>
        <button
          type="button"
          class="fb-sim-mobile-tab"
          :class="{ active: mobileSimTab === 'telemetry' }"
          @click="mobileSimTab = 'telemetry'"
        >
          <span>📊 Radar Tâm lý &amp; Điều phối</span>
          <span v-if="currentInsight" class="sim-m-badge">
            {{ currentInsight.buying_temperature }}%
          </span>
        </button>
      </div>

      <!-- Main Split Layout -->
      <div class="fb-sim-body" :class="'mobile-sim-' + mobileSimTab">
        <!-- Left: Realistic Facebook Messenger Chat View -->
        <div class="messenger-pane">
          <!-- Messenger Header -->
          <div class="messenger-topbar" :class="{ 'is-handoff': isHandedOffToHuman }">
            <div class="page-avatar-row">
              <div class="page-avatar">
                {{ (activePage?.name || 'P').charAt(0).toUpperCase() }}
              </div>
              <div>
                <div class="page-display-name">
                  {{ activePage?.name || 'Fanpage Tư Vấn Tự Động' }}
                </div>
                <div class="bot-live-status">
                  <span class="status-dot" :class="isHandedOffToHuman ? 'dot-handoff' : 'dot-active'"></span>
                  <span v-if="!isHandedOffToHuman">
                    Bot AI đang trực · Nhịp: <strong>{{ formatStageLabel(currentStage) }}</strong>
                  </span>
                  <span v-else class="handoff-status-text">
                    🟠 ĐÃ CHUYỂN NHÂN VIÊN THẬT XỬ LÝ (Bot đã tạm dừng tự động trả lời)
                  </span>
                </div>
              </div>
            </div>

            <div class="messenger-actions">
              <button class="btn-skill-sim" @click="isSkillModalOpen = true" title="Quản lý Kho Kỹ năng Tâm lý, Chốt Sale & Giao tiếp">
                🎯 Kỹ năng Agent
              </button>
              <button class="btn-reset-sim" @click="resetSimulation" title="Làm mới phiên chat của khách">
                🔄 Làm mới phiên
              </button>
            </div>
          </div>

          <!-- Idle Simulation Toolbar -->
          <div class="idle-simulation-bar">
            <span class="idle-bar-label">⏱️ Giả lập Im lặng / Độ trễ:</span>
            <div class="idle-btn-group">
              <button
                class="btn-idle-sim"
                :disabled="isDeliveringBubbles || isSending"
                @click="simulateIdle(300)"
                title="Giả lập khách hàng im lặng không trả lời sau 5 phút (-18% nhiệt độ, kích hoạt Proactive Re-engagement)"
              >
                ⏳ Khách im lặng 5p
              </button>
              <button
                class="btn-idle-sim"
                :disabled="isDeliveringBubbles || isSending"
                @click="simulateIdle(1800)"
                title="Giả lập khách hàng im lặng không trả lời sau 30 phút (-35% nhiệt độ, kích hoạt Proactive Re-engagement)"
              >
                ⏳ Khách im lặng 30p
              </button>
            </div>
          </div>

          <!-- Handoff Banner Alert inside Messenger -->
          <div v-if="isHandedOffToHuman" class="handoff-alert-banner">
            <div class="handoff-alert-main">
              <strong>🚨 Đã đẩy cuộc hội thoại cho Nhân viên / Quản lý xử lý!</strong>
              <span class="handoff-cat-badge">{{ lastHandoffCategory }}</span>
            </div>
            <p class="handoff-reason-text">{{ lastHandoffReason }}</p>
            <button class="btn-resume-bot" @click="isHandedOffToHuman = false">
              Cho phép Bot AI tiếp tục trực lại
            </button>
          </div>

          <!-- Messenger Messages Stream -->
          <div ref="chatScrollRef" class="messenger-messages">
            <div v-if="chatItems.length === 0" class="messenger-empty">
              <p>👋 Hãy đóng vai khách hàng nhắn tin vào Fanpage ở khung bên dưới để kiểm tra trực tiếp phản hồi của Bot AI!</p>
            </div>

            <div
              v-for="(item, idx) in chatItems"
              :key="idx"
              :class="item.role === 'timeline' ? 'timeline-row' : ['msg-bubble-row', item.role === 'user' ? 'is-customer' : 'is-page']"
            >
              <!-- Timeline divider -->
              <div v-if="item.role === 'timeline'" class="timeline-divider-badge">
                {{ item.text }}
              </div>

              <!-- Assistant Avatar -->
              <div v-else-if="item.role === 'assistant'" class="mini-page-avatar">
                {{ (activePage?.name || 'P').charAt(0).toUpperCase() }}
              </div>

              <!-- Assistant message column with bubble, active skills, applied rules, and Teach AI button -->
              <div v-if="item.role === 'assistant'" class="assistant-bubble-col">
                <div
                  class="msg-bubble assistant"
                  :class="[{ 'handoff-bubble': item.isHandoff, 'is-corrected': item.isCorrected, 'is-optimized': item.isOptimized }]"
                >
                  <div v-if="item.isOptimized" class="bubble-optimized-pill">
                    🧬 Harness² Đã tối ưu
                  </div>
                  {{ item.text }}
                </div>

                <div
                  v-if="!isDeliveringBubbles && chatItems[idx + 1]?.role !== 'assistant'"
                  class="assistant-bubble-meta"
                >
                  <!-- In-Loop Deterministic Guardrail Hooks (<5ms) -->
                  <div
                    v-if="item.triggeredHooks && item.triggeredHooks.length > 0"
                    class="triggered-hooks-badge"
                  >
                    <span class="badge-icon">🛡️</span>
                    <span class="badge-label">Guardrail (&lt;5ms):</span>
                    <span
                      v-for="hk in item.triggeredHooks"
                      :key="hk"
                      class="hook-chip"
                      :title="getHookTooltip(hk)"
                    >
                      {{ formatHookLabel(hk) }}
                    </span>
                  </div>

                  <div
                    v-if="item.activeSkills && item.activeSkills.length > 0"
                    class="active-skills-badge"
                  >
                    <span class="badge-icon">⚡</span>
                    <span class="badge-label">Kỹ năng kích hoạt:</span>
                    <span
                      v-for="sk in item.activeSkills"
                      :key="sk.id || sk.code"
                      class="skill-chip-mini"
                      :title="sk.tactical_instructions"
                    >
                      {{ formatSkillCategoryIcon(sk.category) }} {{ sk.name }}
                    </span>
                  </div>

                  <div
                    v-if="item.appliedRules && item.appliedRules.length > 0"
                    class="applied-rules-badge"
                  >
                    <span class="badge-icon">🛡️</span>
                    <span class="badge-label">Đã áp dụng {{ item.appliedRules.length }} quy tắc:</span>
                    <span
                      v-for="r in item.appliedRules"
                      :key="r.id"
                      class="rule-chip"
                      :title="r.generalized_rule"
                    >
                      [{{ r.category || r.dimension || 'RULE' }}] {{ r.generalized_rule ? (r.generalized_rule.length > 25 ? r.generalized_rule.substring(0, 25) + '...' : r.generalized_rule) : '' }}
                    </span>
                  </div>

                  <div class="assistant-actions-group">
                    <button
                      class="btn-teach-fb"
                      title="Báo câu trả lời này bị sai và chỉ dẫn nguyên tắc đúng cho AI"
                      @click="openTeachForMessage(idx)"
                    >
                      💬 Báo sai & Dạy AI
                    </button>
                  </div>
                </div>
              </div>

              <!-- Customer bubble -->
              <div
                v-else-if="item.role === 'user'"
                class="msg-bubble user"
              >
                {{ item.text }}
              </div>
            </div>

            <!-- Typing Indicator -->
            <div v-if="isTyping" class="msg-bubble-row is-page">
              <div class="mini-page-avatar">
                {{ (activePage?.name || 'P').charAt(0).toUpperCase() }}
              </div>
              <div class="msg-bubble assistant typing-indicator">
                <span></span><span></span><span></span>
                <small v-if="thinkingRemainingSec > 0" style="margin-left: 8px; font-size: 12px; color: #b45309; font-weight: 600;">
                  🧠 Đang suy nghĩ giữ nhịp cảm xúc ({{ thinkingRemainingSec }}s)...
                </small>
              </div>
            </div>
          </div>

          <!-- Customer Input Box -->
          <div class="messenger-input-bar">
            <div v-if="isDebouncingBurst" class="burst-typing-banner">
              <span class="burst-dot"></span>
              <span class="burst-text">Đang xử lý tin nhắn...</span>
            </div>
            <div class="messenger-input-row">
              <input
                v-model="customerInput"
                type="text"
                class="messenger-input"
                placeholder="Nhập tin nhắn trong vai khách hàng nhắn cho Fanpage..."
                :disabled="isSending"
                @compositionstart="isImeComposing = true"
                @compositionend="handleImeCompositionEnd($event)"
                @keydown.enter="handleInputEnter($event)"
              />
              <button
                class="btn-send-fb"
                :disabled="!customerInput.trim() || isSending"
                @click="sendCustomerMessage(customerInput)"
              >
                Gửi
              </button>
            </div>
          </div>
        </div>

        <!-- Right: Telemetry, Live Customer Psychology Radar & 6 Real-World Scenarios Panel -->
        <div class="telemetry-pane">
          <!-- Telemetry Status Card -->
          <div class="telemetry-card">
            <div class="telemetry-header">
              <span>📡 Trạng Thái Điều Phối Bot (AstrBot Pipeline)</span>
              <span class="conf-badge">Độ tin cậy: {{ Math.round(lastConfidence * 100) }}%</span>
            </div>

            <div class="stage-stepper">
              <div class="step-item" :class="{ active: currentStage === 'DISCOVERY' }">
                1. Thấu cảm & Hỏi nhu cầu
              </div>
              <div class="step-item" :class="{ active: currentStage === 'VALUE_CONSULTATION' }">
                2. Tư vấn Giá trị
              </div>
              <div class="step-item" :class="{ active: currentStage === 'SOFT_BOOKING_CLOSE' }">
                3. Điều hướng Đặt lịch
              </div>
              <div class="step-item step-handoff" :class="{ active: isHandedOffToHuman || currentStage === 'HUMAN_HANDOFF' }">
                🟠 Chuyển Người Xử Lý
              </div>
            </div>

            <!-- Lead & Booking Capture Box -->
            <div class="lead-capture-box" :class="{ 'has-lead': capturedLead?.has_buying_or_booking_signal }">
              <div class="lead-box-title">
                🎯 Tín hiệu Chốt Sale / Đặt lịch thu thập tự động:
              </div>
              <div v-if="capturedLead?.has_buying_or_booking_signal" class="lead-details">
                <span v-if="capturedLead.phone_number" class="lead-pill">📞 SĐT: {{ capturedLead.phone_number }}</span>
                <span v-if="capturedLead.preferred_time" class="lead-pill">⏰ Hẹn: {{ capturedLead.preferred_time }}</span>
                <span class="lead-note">{{ capturedLead.summary_note }}</span>
              </div>
              <div v-else class="lead-empty">
                Chưa phát hiện SĐT hoặc lịch hẹn cụ thể từ khách hàng.
              </div>
            </div>
          </div>

          <!-- Live Customer Psychology & Insight Radar Card -->
          <div class="insight-radar-card">
            <div class="insight-radar-header">
              <div class="insight-title-row">
                <span>🧠 Radar Thấu hiểu Tâm lý &amp; Insight Khách hàng</span>
              </div>
              <span v-if="currentInsight" class="honorific-pill">
                Xưng hô: <strong>Em – {{ currentInsight.honorific || 'Chị' }}</strong>
              </span>
            </div>

            <div v-if="!currentInsight" class="insight-empty">
              Hãy gửi tin nhắn đầu tiên để AI tự động đọc vị chân dung tâm lý, rào cản ngầm, nhiệt độ mua hàng và kích hoạt Kỹ năng Chuyên gia tương ứng.
            </div>

            <div v-else class="insight-body">
              <!-- Archetype & Buying Temperature -->
              <div class="insight-top-grid">
                <div class="archetype-box">
                  <span class="insight-sub-label">Chân dung tâm lý hiện tại:</span>
                  <div class="archetype-value">{{ currentInsight.archetype_label }}</div>
                </div>

                <div class="temp-box">
                  <div class="temp-label-row">
                    <div class="temp-sub-title-group">
                      <span class="insight-sub-label">Nhiệt độ mua hàng:</span>
                      <span
                        v-if="currentInsight.previous_temperature !== undefined && currentInsight.previous_temperature !== null"
                        class="prev-temp-sub"
                      >
                        (Trước: {{ currentInsight.previous_temperature }}%)
                      </span>
                    </div>

                    <div class="temp-indicators-group">
                      <!-- Delta +/- indicator -->
                      <span
                        v-if="currentInsight.temperature_delta !== undefined && currentInsight.temperature_delta !== null"
                        class="delta-indicator"
                        :class="getDeltaClass(currentInsight.temperature_delta)"
                        :title="'Biến động so với lượt trước: ' + (currentInsight.temperature_delta > 0 ? '+' : '') + currentInsight.temperature_delta + '%'"
                      >
                        {{ formatDelta(currentInsight.temperature_delta) }}
                      </span>

                      <!-- Current Temp badge -->
                      <span class="temp-badge" :class="'temp-' + (currentInsight.temperature_label || 'WARM').toLowerCase()">
                        {{ currentInsight.buying_temperature }}% · {{ currentInsight.temperature_label }}
                      </span>
                    </div>
                  </div>

                  <!-- Progress Track -->
                  <div class="temp-bar-track">
                    <div
                      class="temp-bar-fill"
                      :class="'fill-' + (currentInsight.temperature_label || 'WARM').toLowerCase()"
                      :style="{ width: Math.min(100, Math.max(8, currentInsight.buying_temperature || 40)) + '%' }"
                    ></div>
                  </div>

                  <!-- Momentum badge & decay reason -->
                  <div v-if="currentInsight.momentum" class="momentum-meta-row">
                    <span
                      class="momentum-chip"
                      :class="'momentum-' + currentInsight.momentum.toLowerCase()"
                    >
                      {{ formatMomentumLabel(currentInsight.momentum) }}
                    </span>
                    <span v-if="currentInsight.decay_reason" class="decay-reason-chip" :title="currentInsight.decay_reason">
                      {{ formatDecayReason(currentInsight.decay_reason) }}
                    </span>
                  </div>
                </div>
              </div>

              <!-- Accumulated Pains & Facts -->
              <div v-if="currentInsight.extracted_pains && currentInsight.extracted_pains.length" class="insight-block">
                <span class="insight-sub-label">📌 Nỗi đau &amp; Dữ kiện khách đã chia sẻ (Ghi nhớ xuyên suốt):</span>
                <div class="insight-chips">
                  <span
                    v-for="(pain, pIdx) in currentInsight.extracted_pains"
                    :key="pIdx"
                    class="insight-chip chip-pain"
                  >
                    {{ pain }}
                  </span>
                </div>
              </div>

              <!-- Hidden Concerns -->
              <div v-if="currentInsight.hidden_concerns && currentInsight.hidden_concerns.length" class="insight-block">
                <span class="insight-sub-label">🔍 Rào cản tâm lý &amp; Nỗi sợ ngầm đọc vị được:</span>
                <div class="insight-chips">
                  <span
                    v-for="(concern, cIdx) in currentInsight.hidden_concerns"
                    :key="cIdx"
                    class="insight-chip chip-concern"
                  >
                    {{ concern }}
                  </span>
                </div>
              </div>

              <!-- Recommended Strategy -->
              <div v-if="currentInsight.recommended_strategy" class="strategy-callout">
                <span class="strategy-title">💡 Chiến thuật AI đang thực thi:</span>
                <p class="strategy-text">{{ currentInsight.recommended_strategy }}</p>
              </div>

              <!-- Active Skills Engaged -->
              <div v-if="latestActiveSkills && latestActiveSkills.length" class="insight-block">
                <div class="skills-row-header">
                  <span class="insight-sub-label">⚡ Top Kỹ năng Chuyên gia đang kích hoạt ({{ latestActiveSkills.length }}):</span>
                  <button class="btn-link-mini" @click="isSkillModalOpen = true">Cấu hình Skills →</button>
                </div>
                <div class="insight-chips">
                  <span
                    v-for="sk in latestActiveSkills"
                    :key="sk.id || sk.code"
                    class="insight-chip chip-skill"
                    :title="sk.tactical_instructions"
                  >
                    {{ formatSkillCategoryIcon(sk.category) }} {{ sk.name }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Teach AI Modal Integration -->
      <TeachAiModal
        v-if="teachingTarget"
        :question="teachingTarget.question"
        :faulty-answer="teachingTarget.faultyAnswer"
        :document-ids="teachingTarget.documentIds"
        :message-index="teachingTarget.messageIndex"
        :is-connected="isConnected"
        :pages="pages"
        :active-page-id="teachingTarget.activePageId"
        :history="teachingTarget.history || []"
        :applied-rule-ids="teachingTarget.appliedRuleIds || []"
        source="FANPAGE_BOT"
        @close="teachingTarget = null"
        @submitted="handleTeachSubmitted"
      />

      <!-- Agent Skill Registry Modal -->
      <AgentSkillModal
        v-if="isSkillModalOpen"
        :demo-mode="false"
        :pages="pages"
        :active-page-id="activePage?.id || null"
        @close="isSkillModalOpen = false"
      />

      <!-- Harness Toast Notification -->
      <transition name="harness-fade">
        <div v-if="harnessSuccessToast" class="harness-toast" :class="harnessSuccessToast.type">
          <span class="toast-icon">{{ harnessSuccessToast.type === 'info' ? 'ℹ️' : (harnessSuccessToast.type === 'error' ? '⚠️' : '✨') }}</span>
          <span class="toast-msg">{{ harnessSuccessToast.message }}</span>
        </div>
      </transition>

      <!-- Harness² Contrastive Optimization Modal -->
      <div v-if="isHarnessModalOpen" class="harness-modal-backdrop" @click.self="closeHarnessModal">
        <div class="harness-modal-dialog">
          <!-- Header -->
          <div class="harness-modal-header">
            <div class="harness-title-row">
              <div class="harness-avatar-badge">🧬</div>
              <div>
                <div class="harness-title-wrap">
                  <h4 class="harness-title">Harness² Auto-Optimization Studio</h4>
                  <span v-if="harnessResult?.proposed_dimension" class="dim-pill" :class="harnessResult.proposed_dimension">
                    {{ harnessResult.proposed_dimension === 'INV' ? '🛡️ INV (Quy trình Bất biến)' : '🏢 DOM (Dữ kiện Nghiệp vụ)' }}
                  </span>
                  <span v-if="harnessResult?.run_id || harnessResult?.runId" class="run-id-pill">
                    Run: {{ (harnessResult.run_id || harnessResult.runId || '').substring(0, 12) }}...
                  </span>
                </div>
                <p class="harness-subtitle">
                  Chu trình đệ quy Propose → Probe → Anonymized Select (Google Research Harness²)
                </p>
              </div>
            </div>
            <button class="btn-close-modal" @click="closeHarnessModal">×</button>
          </div>

          <!-- Stepper Workflow Pipeline -->
          <div class="harness-pipeline-bar">
            <div class="pipe-step done">
              <span class="step-num">1</span>
              <span class="step-text">Propose (INV/DOM)</span>
            </div>
            <div class="pipe-connector done"></div>
            <div class="pipe-step done">
              <span class="step-num">2</span>
              <span class="step-text">Probe (Rollout)</span>
            </div>
            <div class="pipe-connector done"></div>
            <div class="pipe-step done">
              <span class="step-num">3</span>
              <span class="step-text">Anonymized Select</span>
            </div>
            <div class="pipe-connector done"></div>
            <div class="pipe-step" :class="harnessResult?.verdict === 'ADOPTED' ? 'step-winner' : 'step-keep'">
              <span class="step-num">4</span>
              <span class="step-text">{{ harnessResult?.verdict || 'Decision' }}</span>
            </div>
          </div>

          <!-- Body -->
          <div class="harness-modal-body">
            <!-- Verdict Banner -->
            <div
              v-if="harnessResult"
              class="verdict-banner"
              :class="harnessResult.verdict === 'ADOPTED' ? 'is-adopted' : 'is-keep'"
            >
              <div class="verdict-icon">
                {{ harnessResult.verdict === 'ADOPTED' ? '🏆' : '🛡️' }}
              </div>
              <div class="verdict-content">
                <div class="verdict-headline">
                  <span class="verdict-tag">{{ harnessResult.verdict }}</span>
                  <span v-if="harnessResult.verdict === 'ADOPTED'">
                    — Đề xuất mới tối ưu hơn! (+{{ formatDeltaScore(harnessResult.delta_score) }} điểm)
                  </span>
                  <span v-else>
                    — Phản hồi ban đầu đã đạt chuẩn tối ưu! (Delta: {{ formatDeltaScore(harnessResult.delta_score) }})
                  </span>
                </div>
                <p class="verdict-sub">
                  <span v-if="harnessResult.verdict === 'ADOPTED'">
                    Ứng viên mới vượt qua vòng đối chiếu mù không thiên lệch và sẵn sàng áp dụng vào hội thoại.
                  </span>
                  <span v-else>
                    Giữ nguyên phản hồi gốc để bảo vệ độ tinh gọn của Playbook và chống suy thoái thư viện kinh nghiệm.
                  </span>
                </p>
              </div>
            </div>

            <!-- Metrics Summary Bar -->
            <div v-if="harnessResult" class="metrics-bar">
              <div class="metric-card">
                <span class="m-label">Điểm Base (Trước):</span>
                <span class="m-val">{{ (harnessResult.score_before || 0).toFixed(2) }}</span>
              </div>
              <div class="metric-arrow">➔</div>
              <div class="metric-card">
                <span class="m-label">Điểm Candidate (Sau):</span>
                <span class="m-val candidate">{{ (harnessResult.score_after || 0).toFixed(2) }}</span>
              </div>
              <div class="metric-card highlight">
                <span class="m-label">ΔScore Cải thiện:</span>
                <span class="m-val delta" :class="harnessResult.delta_score > 0 ? 'pos' : 'neg'">
                  {{ formatDeltaScore(harnessResult.delta_score) }}
                </span>
              </div>
            </div>

            <!-- Anonymized Comparison Section (Candidate A vs Candidate B) -->
            <div v-if="harnessResult" class="comparison-grid">
              <!-- Candidate A Card -->
              <div
                class="candidate-card"
                :class="{ 'is-winner': harnessResult.winner_candidate === 'Candidate A' || (harnessResult.verdict === 'KEEP_BASE' && harnessResult.candidate_a_label === 'BASE') }"
              >
                <div class="candidate-header">
                  <div class="cand-title-row">
                    <span class="cand-name">Ứng viên A</span>
                    <span class="cand-label-revealed" :class="harnessResult.candidate_a_label">
                      [{{ harnessResult.candidate_a_label === 'BASE' ? 'Bản gốc Base' : 'Ứng viên Harness²' }}]
                    </span>
                  </div>
                  <span
                    v-if="harnessResult.winner_candidate === 'Candidate A'"
                    class="winner-badge"
                  >
                    👑 CHIẾN THẮNG
                  </span>
                </div>
                <div class="candidate-body">
                  <div
                    v-for="(para, pIdx) in splitTextBubbles(harnessResult.candidate_a)"
                    :key="pIdx"
                    class="cand-bubble"
                  >
                    {{ para }}
                  </div>
                </div>
              </div>

              <!-- Candidate B Card -->
              <div
                class="candidate-card"
                :class="{ 'is-winner': harnessResult.winner_candidate === 'Candidate B' || (harnessResult.verdict === 'KEEP_BASE' && harnessResult.candidate_b_label === 'BASE') }"
              >
                <div class="candidate-header">
                  <div class="cand-title-row">
                    <span class="cand-name">Ứng viên B</span>
                    <span class="cand-label-revealed" :class="harnessResult.candidate_b_label">
                      [{{ harnessResult.candidate_b_label === 'BASE' ? 'Bản gốc Base' : 'Ứng viên Harness²' }}]
                    </span>
                  </div>
                  <span
                    v-if="harnessResult.winner_candidate === 'Candidate B'"
                    class="winner-badge"
                  >
                    👑 CHIẾN THẮNG
                  </span>
                </div>
                <div class="candidate-body">
                  <div
                    v-for="(para, pIdx) in splitTextBubbles(harnessResult.candidate_b)"
                    :key="pIdx"
                    class="cand-bubble"
                  >
                    {{ para }}
                  </div>
                </div>
              </div>
            </div>

            <!-- Selection Rationale -->
            <div v-if="harnessResult?.rationale" class="rationale-box">
              <div class="rat-title">💡 Lý do Lựa chọn &amp; Đánh giá Đối chứng (Selection Rationale):</div>
              <p class="rat-text">{{ harnessResult.rationale }}</p>
            </div>

            <!-- Experience Library Card (Cue – Lesson – Contrastive Evidence) -->
            <div v-if="harnessResult && harnessResult.verdict === 'ADOPTED'" class="experience-library-card">
              <div class="exp-header">
                <span class="exp-icon">📚</span>
                <span class="exp-title">Thẻ Kinh nghiệm Đúc kết vào Playbook:</span>
                <span class="exp-dim-badge">{{ harnessResult.proposed_dimension || 'INV' }}</span>
              </div>
              <div class="exp-body">
                <div class="exp-row">
                  <span class="exp-tag cue">🎯 Cue Signal (Tình huống kích hoạt):</span>
                  <div class="exp-val">{{ harnessResult.proposed_cue || 'Ngữ cảnh do dự / hạ nhiệt' }}</div>
                </div>
                <div class="exp-row">
                  <span class="exp-tag lesson">💡 Lesson (Bài học quy tắc):</span>
                  <div class="exp-val">{{ harnessResult.proposed_lesson }}</div>
                </div>
                <div v-if="harnessResult.contrastive_evidence" class="exp-row">
                  <span class="exp-tag evidence">⚖️ Contrastive Evidence:</span>
                  <pre class="exp-evidence-box">{{ harnessResult.contrastive_evidence }}</pre>
                </div>
              </div>
            </div>
          </div>

          <!-- Footer Actions -->
          <div class="harness-modal-footer">
            <button
              class="btn-conv-audit-link"
              @click="switchToWholeConversationAudit"
              title="Đọc và kiểm toán toàn bộ cuộc hội thoại từ đầu đến cuối thay vì chỉ 1 câu"
            >
              🧬 Đánh giá &amp; Sửa Toàn Hội Thoại
            </button>

            <button class="btn-cancel-harness" @click="closeHarnessModal">
              {{ harnessResult?.verdict === 'ADOPTED' ? 'Bỏ qua (Không áp dụng)' : 'Đóng' }}
            </button>

            <button
              v-if="harnessResult?.verdict === 'ADOPTED'"
              class="btn-apply-harness"
              @click="applyHarnessWinningReply"
            >
              ✨ Cập nhật câu trả lời này vào Hội thoại
            </button>

            <button
              v-else
              class="btn-confirm-keep"
              @click="closeHarnessModal"
            >
              🛡️ Đã hiểu (Giữ nguyên phản hồi gốc)
            </button>
          </div>
        </div>
      </div>

      <!-- Whole-Conversation Audit & Multi-Turn Optimization Studio Modal -->
      <div v-if="isConversationAuditModalOpen && conversationAuditResult" class="harness-modal-overlay" @click.self="closeConversationAuditModal">
        <div class="harness-modal-card conv-audit-card">
          <div class="harness-modal-header">
            <div class="harness-header-left">
              <div class="harness-logo-badge">🧬</div>
              <div>
                <h4 class="harness-modal-title">
                  Harness² Kiểm Toán &amp; Tối Ưu Toàn Hội Thoại (Whole-Conversation Audit)
                  <span
                    class="verdict-pill"
                    :class="conversationAuditResult.issues_found_count > 0 ? 'is-adopted' : 'is-keep'"
                  >
                    {{ conversationAuditResult.issues_found_count > 0 ? `🔍 Phát hiện ${conversationAuditResult.issues_found_count}/${conversationAuditResult.total_exchanges} lượt cần tối ưu` : '🛡️ Toàn hội thoại đạt chuẩn tối ưu' }}
                  </span>
                </h4>
                <p class="harness-modal-subtitle">
                  Đọc xuyên suốt {{ conversationAuditResult.total_exchanges }} lượt trao đổi · Phát hiện câu hỏi thừa sau khi chốt giờ/SĐT · Chuẩn hóa phản biện giá &amp; đúc kết quy tắc Playbook
                </p>
              </div>
            </div>
            <button class="btn-close-harness" @click="closeConversationAuditModal">×</button>
          </div>

          <div class="harness-modal-body">
            <!-- Score & Cumulative State Bar -->
            <div class="harness-metrics-bar">
              <div class="h-metric-item">
                <span class="h-m-label">Điểm Hội thoại Trước</span>
                <span class="h-m-val">{{ Number(conversationAuditResult.overall_score_before || 0).toFixed(2) }}</span>
              </div>
              <div class="h-metric-arrow">➔</div>
              <div class="h-metric-item highlight">
                <span class="h-m-label">Điểm Sau Tối ưu</span>
                <span class="h-m-val">{{ Number(conversationAuditResult.overall_score_after || 0).toFixed(2) }}</span>
              </div>
              <div v-if="conversationAuditResult.state_summary" class="h-metric-item">
                <span class="h-m-label">Danh xưng / Khung giờ đã chốt</span>
                <span class="h-m-val delta-pos">
                  {{ conversationAuditResult.state_summary.resolved_honorific || 'Chị' }} · {{ conversationAuditResult.state_summary.confirmed_time_slot || 'Chưa chốt giờ' }}
                </span>
              </div>
              <div v-if="conversationAuditResult.state_summary?.captured_phone" class="h-metric-item">
                <span class="h-m-label">SĐT Đã Thu Thập</span>
                <span class="h-m-val delta-pos">📞 {{ conversationAuditResult.state_summary.captured_phone }}</span>
              </div>
            </div>

            <!-- Executive Summary -->
            <div class="rationale-box">
              <div class="rat-title">📋 Tổng kết Đánh giá Toàn Hội Thoại (Executive Summary):</div>
              <p class="rat-text">{{ conversationAuditResult.executive_summary }}</p>
            </div>

            <!-- Turn-by-Turn Findings List -->
            <div class="conv-findings-list">
              <div
                v-for="finding in conversationAuditResult.findings"
                :key="finding.exchange_index"
                class="conv-finding-item"
                :class="finding.severity === 'OPTIMAL' ? 'is-optimal-turn' : 'is-faulty-turn'"
              >
                <div class="conv-finding-header">
                  <div class="conv-finding-left">
                    <span class="turn-badge">Lượt #{{ finding.exchange_index }}</span>
                    <span
                      class="severity-badge"
                      :class="'sev-' + (finding.severity || 'OPTIMAL').toLowerCase()"
                    >
                      {{ formatFindingCategory(finding.issue_category, finding.severity) }}
                    </span>
                  </div>
                  <button
                    v-if="finding.severity !== 'OPTIMAL'"
                    class="btn-apply-single-turn"
                    @click="applySingleTurnAuditFix(finding)"
                  >
                    ✨ Áp dụng sửa lượt #{{ finding.exchange_index }}
                  </button>
                </div>

                <div class="conv-customer-quote">
                  <strong>👤 Khách hàng:</strong> "{{ finding.customer_message }}"
                </div>

                <div class="conv-diagnosis-grid">
                  <div class="diag-box issue">
                    <div class="diag-label">🔍 Đánh giá / Điểm chưa tốt:</div>
                    <div class="diag-text">{{ finding.issue_summary }}</div>
                  </div>
                  <div class="diag-box fix">
                    <div class="diag-label">🛠️ Hướng xử lý của Harness²:</div>
                    <div class="diag-text">{{ finding.recommended_fix }}</div>
                  </div>
                </div>

                <!-- Before vs After Comparison for Faulty Turns -->
                <div v-if="finding.severity !== 'OPTIMAL'" class="candidates-comparison-grid conv-turn-diff">
                  <div class="candidate-col">
                    <div class="candidate-header">
                      <span class="candidate-id-badge">❌ Phản hồi Cũ (Có điểm thừa/lỗi)</span>
                    </div>
                    <div class="candidate-body">
                      <div
                        v-for="(para, pIdx) in splitTextBubbles(finding.original_reply)"
                        :key="pIdx"
                        class="cand-bubble"
                      >
                        {{ para }}
                      </div>
                    </div>
                  </div>
                  <div class="candidate-col is-winner">
                    <div class="candidate-header">
                      <span class="candidate-id-badge">✅ Phản hồi Đã Tối Ưu Xuyên Lượt</span>
                      <span class="winner-badge">👑 ĐỀ XUẤT</span>
                    </div>
                    <div class="candidate-body">
                      <div
                        v-for="(para, pIdx) in (finding.improved_bubbles && finding.improved_bubbles.length ? finding.improved_bubbles : splitTextBubbles(finding.improved_reply))"
                        :key="pIdx"
                        class="cand-bubble"
                      >
                        {{ para }}
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Learned Rules from Whole Conversation -->
            <div
              v-if="conversationAuditResult.learned_rules && conversationAuditResult.learned_rules.length > 0"
              class="experience-library-card"
            >
              <div class="exp-header">
                <span class="exp-icon">📚</span>
                <span class="exp-title">
                  Đã tự động đúc kết {{ conversationAuditResult.learned_rules.length }} Quy tắc (INV) vào Bộ nhớ Playbook:
                </span>
              </div>
              <div class="exp-body">
                <div
                  v-for="(rule, rIdx) in conversationAuditResult.learned_rules"
                  :key="rule.id || rIdx"
                  class="exp-row"
                >
                  <span class="exp-tag lesson">💡 Quy tắc #{{ rIdx + 1 }} ({{ rule.category || 'SALES_STRATEGY' }}):</span>
                  <div class="exp-val">{{ rule.generalized_rule }}</div>
                </div>
              </div>
            </div>
          </div>

          <div class="harness-modal-footer">
            <button class="btn-cancel-harness" @click="closeConversationAuditModal">
              Đóng
            </button>
            <button
              v-if="conversationAuditResult.issues_found_count > 0"
              class="btn-apply-harness"
              @click="applyAllConversationAuditFixes"
            >
              ✨ Áp dụng Sửa Tất Cả ({{ conversationAuditResult.issues_found_count }} lượt) Vào Hội Thoại
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onBeforeUnmount } from 'vue'
import { api } from '../api'
import TeachAiModal from './TeachAiModal.vue'
import AgentSkillModal from './AgentSkillModal.vue'

const props = defineProps({
  isOpen: {
    type: Boolean,
    default: false
  },
  activePage: {
    type: Object,
    default: null
  },
  pages: {
    type: Array,
    default: () => []
  },
  selectedDocs: {
    type: Array,
    default: () => []
  },
  isConnected: {
    type: Boolean,
    default: true
  }
})

defineEmits(['close'])

const customerInput = ref('')
const mobileSimTab = ref('chat') // 'chat' | 'telemetry'
const chatItems = ref([])
const conversationHistory = ref([])
const createSimConversationId = () => `sim_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
const conversationId = ref(createSimConversationId())
const pendingResetSession = ref(false)
const isTyping = ref(false)
const isDeliveringBubbles = ref(false)
const isSending = ref(false)
const isDebouncingBurst = ref(false)
const pendingBurstBuffer = ref([])
let burstDebounceTimer = null

const currentStage = ref('DISCOVERY')
const lastConfidence = ref(0.9)
const isHandedOffToHuman = ref(false)
const lastHandoffCategory = ref(null)
const lastHandoffReason = ref(null)
const capturedLead = ref(null)
const currentInsight = ref(null)
const latestActiveSkills = ref([])
const isSkillModalOpen = ref(false)
const chatScrollRef = ref(null)

// =========================================================================
// HARNESS² RECURSIVE OPTIMIZATION & GUARDRAILS STATE
// =========================================================================
const isOptimizingHarness = ref(false)
const optimizingAssistantIndex = ref(null)
const isHarnessModalOpen = ref(false)
const harnessResult = ref(null)
const harnessTarget = ref(null)
const isAuditingConversation = ref(false)
const isConversationAuditModalOpen = ref(false)
const conversationAuditResult = ref(null)
const harnessSuccessToast = ref(null)
let harnessToastTimer = null

const showHarnessToast = (msg, type = 'success') => {
  if (harnessToastTimer) clearTimeout(harnessToastTimer)
  harnessSuccessToast.value = { message: msg, type }
  harnessToastTimer = setTimeout(() => {
    harnessSuccessToast.value = null
    harnessToastTimer = null
  }, 4500)
}

const formatSkillCategoryIcon = (category) => {
  if (category === 'PSYCHOLOGY') return '🧠'
  if (category === 'COMMUNICATION') return '💬'
  return '🎯'
}

const formatDelta = (delta) => {
  if (delta > 0) return `+${delta}% ↑`
  if (delta < 0) return `${delta}% ↓`
  return '0% ➔'
}

const getDeltaClass = (delta) => {
  if (delta > 0) return 'delta-pos'
  if (delta < 0) return 'delta-neg'
  return 'delta-neutral'
}

const formatMomentumLabel = (momentum) => {
  const map = {
    RISING: '🔥 Đang nóng lên (RISING)',
    STABLE: '⚖️ Ổn định (STABLE)',
    COOLING_HALF_HEARTED: '❄️ Hạ nhiệt (Nửa vời / Thoái thác)',
    COOLING_IDLE: '⏳ Hạ nhiệt (Im lặng quá lâu)',
    RECOVERING: '🌱 Đang phục hồi (RECOVERING)'
  }
  return map[momentum] || momentum
}

const formatDecayReason = (reason) => {
  const map = {
    COOLING_HALF_HEARTED: 'Khách trả lời nửa vời / thoái thác',
    COOLING_IDLE: 'Khách im lặng lâu không phản hồi',
    PRICE_HESITATION: 'Khách ngần ngại về giá'
  }
  return map[reason] || reason
}

const formatStageLabel = (stage) => {
  const map = {
    DISCOVERY: '1. Thấu cảm & Khai thác nhu cầu',
    VALUE_CONSULTATION: '2. Tư vấn Giá trị & Giải tỏa băn khoăn',
    SOFT_BOOKING_CLOSE: '3. Điều hướng Đặt lịch / Chốt Sale',
    HUMAN_HANDOFF: 'Chuyển Nhân Viên Thật'
  }
  return map[stage] || stage
}

const scrollToBottom = () => {
  nextTick(() => {
    if (chatScrollRef.value) {
      chatScrollRef.value.scrollTop = chatScrollRef.value.scrollHeight
    }
  })
}

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))
const thinkingRemainingSec = ref(0)

let bubbleDeliveryGeneration = 0

const filterDisplayedAppliedRules = (rules, isHandoff) => {
  if (!Array.isArray(rules)) return []
  return rules.filter(r => {
    const text = `${r?.generalized_rule || ''} ${r?.instruction || ''}`
    if ((text.includes('[SAFE-') || text.includes('HARD_HANDOFF_SAFETY')) && !isHandoff) {
      return false
    }
    return true
  })
}

const deliverBubbles = async (response) => {
  const myGen = ++bubbleDeliveryGeneration
  const bubbles = response.reply_bubbles && response.reply_bubbles.length > 0
    ? response.reply_bubbles
    : [{ text: response.reply_text, typing_delay_ms: 1000 }]

  const appliedRules = filterDisplayedAppliedRules(response.applied_rules || [], Boolean(response.needs_human_handoff))
  const activeSkills = response.active_skills || []
  const triggeredHooks = response.triggered_hooks || response.triggeredHooks || []

  isDeliveringBubbles.value = true
  try {
    for (let i = 0; i < bubbles.length; i++) {
      if (myGen !== bubbleDeliveryGeneration) return
      if (i > 0) {
        isTyping.value = true
        scrollToBottom()
        const rawDelay = bubbles[i].typing_delay_ms || 450
        if (rawDelay >= 5000) {
          const totalSec = Math.round(rawDelay / 1000)
          for (let sec = totalSec; sec >= 1; sec--) {
            if (myGen !== bubbleDeliveryGeneration) return
            thinkingRemainingSec.value = sec
            scrollToBottom()
            await sleep(1000)
          }
          thinkingRemainingSec.value = 0
        } else {
          thinkingRemainingSec.value = 0
          const delay = Math.max(450, Math.min(900, rawDelay))
          await sleep(delay)
        }
      }
      if (myGen !== bubbleDeliveryGeneration) return
      isTyping.value = false
      thinkingRemainingSec.value = 0
      chatItems.value.push({
        role: 'assistant',
        text: bubbles[i].text,
        isHandoff: response.needs_human_handoff,
        appliedRules: i === bubbles.length - 1 ? appliedRules : [],
        activeSkills: i === bubbles.length - 1 ? activeSkills : [],
        triggeredHooks: i === bubbles.length - 1 ? triggeredHooks : []
      })
      scrollToBottom()
    }
  } finally {
    if (myGen === bubbleDeliveryGeneration) {
      isDeliveringBubbles.value = false
      isTyping.value = false
      thinkingRemainingSec.value = 0
    }
  }
}

const isImeComposing = ref(false)
let lastEnterHandledAt = 0

const handleImeCompositionEnd = (event) => {
  isImeComposing.value = false
  if (event && event.target && typeof event.target.value === 'string') {
    customerInput.value = event.target.value
  }
}

const handleInputEnter = (event) => {
  if (!event) return
  event.preventDefault()
  const now = Date.now()
  if (now - lastEnterHandledAt < 80) {
    return
  }
  // Khi bộ gõ tiếng Việt (macOS Telex / EVKey / OpenKey) đang compose từ cuối (VD: "tư vấn", "k hiểu"),
  // chờ 35ms để bộ gõ commit xong chữ có dấu vào input.value rồi mới gửi 1 lần duy nhất.
  if (event.isComposing || event.keyCode === 229 || isImeComposing.value) {
    const targetEl = event.target
    setTimeout(() => {
      isImeComposing.value = false
      const committedText = (targetEl && typeof targetEl.value === 'string' ? targetEl.value : customerInput.value) || ''
      if (committedText.trim()) {
        lastEnterHandledAt = Date.now()
        sendCustomerMessage(committedText)
        if (targetEl) targetEl.value = ''
      }
    }, 35)
    return
  }
  lastEnterHandledAt = now
  const liveVal = (event.target && typeof event.target.value === 'string' ? event.target.value : customerInput.value) || ''
  sendCustomerMessage(liveVal)
}

const sendCustomerMessage = (rawText) => {
  const text = (rawText || '').trim()
  if (!text || isSending.value) return
  if (isDeliveringBubbles.value) {
    // cancel_pending_if_customer_replies = true: hủy ngay các bong bóng đang chờ của lượt cũ khi khách nhắn tiếp
    bubbleDeliveryGeneration++
    isDeliveringBubbles.value = false
    isTyping.value = false
    thinkingRemainingSec.value = 0
  }

  customerInput.value = ''

  // Chống tách đôi tin nhắn do bộ gõ tiếng Việt (VD: gửi "k" rồi 20ms sau gửi "k hiểu", hoặc "tư" rồi "tư vấn")
  if (pendingBurstBuffer.value.length > 0) {
    const lastBurstIdx = pendingBurstBuffer.value.length - 1
    const prevText = (pendingBurstBuffer.value[lastBurstIdx] || '').trim()
    const prevLower = prevText.toLowerCase()
    const curLower = text.toLowerCase()

    if (curLower === prevLower || prevLower.startsWith(curLower + ' ')) {
      return
    }
    if (curLower.startsWith(prevLower + ' ') || (prevLower.length <= 4 && curLower.startsWith(prevLower))) {
      pendingBurstBuffer.value[lastBurstIdx] = text
      for (let i = chatItems.value.length - 1; i >= 0; i--) {
        if (chatItems.value[i].role === 'user' && chatItems.value[i].text === prevText) {
          chatItems.value[i].text = text
          break
        }
      }
      if (burstDebounceTimer) {
        clearTimeout(burstDebounceTimer)
      }
      burstDebounceTimer = setTimeout(() => {
        burstDebounceTimer = null
        flushBurst()
      }, 350)
      return
    }
  }

  chatItems.value.push({ role: 'user', text })
  scrollToBottom()

  pendingBurstBuffer.value.push(text)
  isDebouncingBurst.value = true

  if (burstDebounceTimer) {
    clearTimeout(burstDebounceTimer)
  }
  burstDebounceTimer = setTimeout(() => {
    burstDebounceTimer = null
    flushBurst()
  }, 350)
}

const deliverDelayedAnchorBubble = async (anchorResponse, myGen, assistantHistoryIdx) => {
  if (!anchorResponse || !anchorResponse.reply_bubbles || anchorResponse.reply_bubbles.length === 0) {
    return
  }
  const bubble2 = anchorResponse.reply_bubbles[0]
  if (!bubble2 || !bubble2.text) {
    return
  }
  const appliedRules = filterDisplayedAppliedRules(anchorResponse.applied_rules || [], Boolean(anchorResponse.needs_human_handoff))
  const activeSkills = anchorResponse.active_skills || []
  const triggeredHooks = anchorResponse.triggered_hooks || anchorResponse.triggeredHooks || []

  isDeliveringBubbles.value = true
  isTyping.value = true
  scrollToBottom()
  try {
    const rawDelay = bubble2.typing_delay_ms ?? 0
    if (rawDelay >= 1000) {
      const totalSec = Math.max(1, Math.round(rawDelay / 1000))
      for (let sec = totalSec; sec >= 1; sec--) {
        if (myGen !== bubbleDeliveryGeneration) return
        thinkingRemainingSec.value = sec
        scrollToBottom()
        await sleep(1000)
      }
      thinkingRemainingSec.value = 0
    } else if (rawDelay > 0) {
      thinkingRemainingSec.value = 0
      await sleep(rawDelay)
    }
    if (myGen !== bubbleDeliveryGeneration) return
    isTyping.value = false
    thinkingRemainingSec.value = 0
    chatItems.value.push({
      role: 'assistant',
      text: bubble2.text,
      isHandoff: anchorResponse.needs_human_handoff,
      appliedRules,
      activeSkills,
      triggeredHooks
    })
    if (
      assistantHistoryIdx >= 0 &&
      assistantHistoryIdx < conversationHistory.value.length &&
      conversationHistory.value[assistantHistoryIdx]?.role === 'assistant'
    ) {
      conversationHistory.value[assistantHistoryIdx].content =
        `${conversationHistory.value[assistantHistoryIdx].content}\n\n${bubble2.text}`
    } else {
      conversationHistory.value.push({ role: 'assistant', content: bubble2.text })
    }
    scrollToBottom()
  } finally {
    if (myGen === bubbleDeliveryGeneration) {
      isDeliveringBubbles.value = false
      isTyping.value = false
      thinkingRemainingSec.value = 0
    }
  }
}

const flushBurst = async () => {
  if (pendingBurstBuffer.value.length === 0) {
    isDebouncingBurst.value = false
    return
  }

  const messagesToSend = [...pendingBurstBuffer.value]
  pendingBurstBuffer.value = []
  isDebouncingBurst.value = false
  isSending.value = true
  isTyping.value = true
  scrollToBottom()

  let turnGen = null
  try {
    const singleMessage = messagesToSend.join('. ')
    const docIds = props.selectedDocs.map(d => d.document_id)
    const prevTemp = currentInsight.value?.buying_temperature ?? null
    const historySnapshotBeforeSend = [...conversationHistory.value]

    const shouldResetSession = pendingResetSession.value
    pendingResetSession.value = false

    const response = await api.sendFanpageBotMessage({
      pageId: props.activePage?.id || null,
      senderId: 'fb-customer-demo',
      conversationId: conversationId.value,
      resetSession: shouldResetSession,
      customerName: null,
      message: singleMessage,
      messages: messagesToSend.length > 1 ? messagesToSend : null,
      history: historySnapshotBeforeSend,
      documentIds: docIds,
      previousTemperature: prevTemp,
      topK: 5,
      splitPriceBubbles: false
    })

    // Cập nhật lịch sử hội thoại
    for (const msg of messagesToSend) {
      conversationHistory.value.push({ role: 'user', content: msg })
    }
    conversationHistory.value.push({ role: 'assistant', content: response.reply_text })
    const assistantHistoryIdx = conversationHistory.value.length - 1

    currentStage.value = response.sales_stage || 'DISCOVERY'
    lastConfidence.value = response.confidence_score ?? 0.85

    if (response.customer_insight) {
      currentInsight.value = response.customer_insight
    }
    if (response.active_skills) {
      latestActiveSkills.value = response.active_skills
    }

    if (response.extracted_lead && response.extracted_lead.has_buying_or_booking_signal) {
      capturedLead.value = response.extracted_lead
    }

    if (response.needs_human_handoff) {
      isHandedOffToHuman.value = true
      lastHandoffCategory.value = response.handoff_category || 'HUMAN_HANDOFF'
      lastHandoffReason.value = response.handoff_reason || 'Tình huống cần chuyên viên trực tiếp xử lý.'
    }

    await deliverBubbles(response)
    turnGen = bubbleDeliveryGeneration

    if (response.pending_second_bubble && turnGen === bubbleDeliveryGeneration) {
      const anchorGen = turnGen
      isSending.value = false
      isDeliveringBubbles.value = true
      isTyping.value = true
      scrollToBottom()

      const anchorResponse = await api.sendFanpageBotMessage({
        pageId: props.activePage?.id || null,
        senderId: 'fb-customer-demo',
        conversationId: conversationId.value,
        customerName: null,
        message: singleMessage,
        messages: messagesToSend.length > 1 ? messagesToSend : null,
        history: historySnapshotBeforeSend,
        documentIds: docIds,
        previousTemperature: prevTemp,
        topK: 5,
        action: 'GENERATE_PRICE_ANCHOR_BUBBLE',
        pendingTurnSequence: response.pending_turn_sequence,
        priceBubbleSentAtNs: response.price_bubble_sent_at_ns
      })

      if (anchorGen === bubbleDeliveryGeneration) {
        await deliverDelayedAnchorBubble(anchorResponse, anchorGen, assistantHistoryIdx)
      }
    }
  } catch (err) {
    if (turnGen === null || turnGen === bubbleDeliveryGeneration) {
      chatItems.value.push({
        role: 'timeline',
        text: `⚠️ Kết nối tạm thời gián đoạn (${err.message || 'Lỗi mạng'}). Bạn có thể gửi lại tin nhắn ngay bên dưới.`
      })
    }
  } finally {
    if (turnGen === null || turnGen === bubbleDeliveryGeneration) {
      isSending.value = false
      isTyping.value = false
      isDeliveringBubbles.value = false
      thinkingRemainingSec.value = 0
      scrollToBottom()
    }
  }
}

const simulateIdle = async (seconds) => {
  if (isTyping.value || isDeliveringBubbles.value || isSending.value) return

  if (burstDebounceTimer) {
    clearTimeout(burstDebounceTimer)
    burstDebounceTimer = null
  }
  pendingBurstBuffer.value = []
  isDebouncingBurst.value = false

  const label = seconds >= 1800 ? '30 phút' : '5 phút'
  chatItems.value.push({
    role: 'timeline',
    text: `⏳ [Giả lập: Khách hàng im lặng không phản hồi sau ${label}...]`
  })
  scrollToBottom()

  isSending.value = true
  isTyping.value = true

  try {
    const docIds = props.selectedDocs.map(d => d.document_id)
    const prevTemp = currentInsight.value?.buying_temperature ?? 70

    const response = await api.sendFanpageBotMessage({
      pageId: props.activePage?.id || null,
      senderId: 'fb-customer-demo',
      conversationId: conversationId.value,
      customerName: null,
      message: '',
      history: conversationHistory.value,
      documentIds: docIds,
      idleSeconds: seconds,
      replyDelaySeconds: seconds,
      previousTemperature: prevTemp,
      action: 'REENGAGE',
      isIdleReengage: true,
      topK: 5
    })

    conversationHistory.value.push({ role: 'assistant', content: response.reply_text })

    currentStage.value = response.sales_stage || 'DISCOVERY'
    lastConfidence.value = response.confidence_score ?? 0.85

    if (response.customer_insight) {
      currentInsight.value = response.customer_insight
    }
    if (response.active_skills) {
      latestActiveSkills.value = response.active_skills
    }

    if (response.extracted_lead && response.extracted_lead.has_buying_or_booking_signal) {
      capturedLead.value = response.extracted_lead
    }

    if (response.needs_human_handoff) {
      isHandedOffToHuman.value = true
      lastHandoffCategory.value = response.handoff_category || 'HUMAN_HANDOFF'
      lastHandoffReason.value = response.handoff_reason || 'Tình huống cần chuyên viên trực tiếp xử lý.'
    }

    await deliverBubbles(response)
  } catch (err) {
    chatItems.value.push({
      role: 'timeline',
      text: `⚠️ Kết nối tạm thời gián đoạn (${err.message || 'Lỗi mạng'}). Bạn có thể gửi lại tin nhắn ngay bên dưới.`
    })
  } finally {
    isSending.value = false
    isTyping.value = false
    scrollToBottom()
  }
}

const teachingTarget = ref(null)

const buildTurnHistoryFromChatItems = (itemsSlice) => {
  const turns = []
  for (const item of itemsSlice) {
    if (!item || !item.text || item.role === 'timeline') continue
    const last = turns[turns.length - 1]
    if (last && last.role === item.role) {
      last.content = `${last.content}\n\n${item.text}`
    } else {
      turns.push({ role: item.role, content: item.text })
    }
  }
  return turns.slice(-30)
}

const openTeachForMessage = (assistantIdx) => {
  let turnStartIdx = assistantIdx
  while (turnStartIdx > 0 && chatItems.value[turnStartIdx - 1]?.role === 'assistant') {
    turnStartIdx--
  }
  let turnEndIdx = assistantIdx
  while (turnEndIdx + 1 < chatItems.value.length && chatItems.value[turnEndIdx + 1]?.role === 'assistant') {
    turnEndIdx++
  }

  let customerQuestion = ''
  let questionIdx = -1
  for (let i = turnStartIdx - 1; i >= 0; i--) {
    if (chatItems.value[i].role === 'user') {
      customerQuestion = chatItems.value[i].text
      questionIdx = i
      break
    }
  }
  if (!customerQuestion) {
    customerQuestion = 'Tư vấn trên Fanpage'
  }

  const faultyAnswer = chatItems.value
    .slice(turnStartIdx, turnEndIdx + 1)
    .map(item => item.text || '')
    .filter(Boolean)
    .join('\n\n')
  const priorHistory = questionIdx > 0
    ? buildTurnHistoryFromChatItems(chatItems.value.slice(0, questionIdx))
    : []
  const docIds = (props.selectedDocs || []).map(d => d.document_id)
  const appliedRuleIds = Array.from(new Set(
    chatItems.value
      .slice(turnStartIdx, turnEndIdx + 1)
      .flatMap(item => item.appliedRules || [])
      .map(r => r?.id)
      .filter(Boolean)
  ))

  teachingTarget.value = {
    messageIndex: assistantIdx,
    turnStartIndex: turnStartIdx,
    turnEndIndex: turnEndIdx,
    question: customerQuestion,
    faultyAnswer: faultyAnswer,
    documentIds: docIds,
    activePageId: props.activePage?.id || null,
    history: priorHistory,
    appliedRuleIds
  }
}

const handleTeachSubmitted = ({ messageIndex, learnedRule, regeneratedAnswer, appliedRules, customerInsight, activeSkills }) => {
  const startIdx = teachingTarget.value?.turnStartIndex ?? messageIndex
  const endIdx = teachingTarget.value?.turnEndIndex ?? messageIndex

  if (startIdx >= 0 && startIdx < chatItems.value.length) {
    const paragraphs = (regeneratedAnswer || '')
      .split(/\n{2,}/)
      .map(p => p.trim())
      .filter(Boolean)
    const finalParagraphs = paragraphs.length > 0 ? paragraphs : [regeneratedAnswer || '']
    const resolvedRules = appliedRules || (learnedRule ? [learnedRule] : [])
    const resolvedSkills = activeSkills && activeSkills.length > 0
      ? activeSkills
      : (chatItems.value[endIdx]?.activeSkills || [])

    const replacementBubbles = finalParagraphs.map((para, idx) => ({
      role: 'assistant',
      text: para,
      isHandoff: false,
      isCorrected: true,
      appliedRules: idx === finalParagraphs.length - 1 ? resolvedRules : [],
      activeSkills: idx === finalParagraphs.length - 1 ? resolvedSkills : []
    }))

    chatItems.value.splice(startIdx, endIdx - startIdx + 1, ...replacementBubbles)

    if (activeSkills && activeSkills.length > 0) {
      latestActiveSkills.value = activeSkills
    }
  }
  if (customerInsight) {
    currentInsight.value = customerInsight
  }

  // Đồng bộ lại conversationHistory từ chatItems để các lượt tiếp theo nhận đầy đủ lịch sử đã sửa
  conversationHistory.value = buildTurnHistoryFromChatItems(chatItems.value)

  teachingTarget.value = null
}

const resetSimulation = () => {
  if (burstDebounceTimer) {
    clearTimeout(burstDebounceTimer)
    burstDebounceTimer = null
  }
  if (harnessToastTimer) {
    clearTimeout(harnessToastTimer)
    harnessToastTimer = null
  }
  pendingBurstBuffer.value = []
  isDebouncingBurst.value = false
  isDeliveringBubbles.value = false
  isSending.value = false
  isTyping.value = false
  chatItems.value = []
  conversationHistory.value = []
  conversationId.value = createSimConversationId()
  pendingResetSession.value = true
  currentStage.value = 'DISCOVERY'
  lastConfidence.value = 0.9
  isHandedOffToHuman.value = false
  lastHandoffCategory.value = null
  lastHandoffReason.value = null
  capturedLead.value = null
  currentInsight.value = null
  latestActiveSkills.value = []
  teachingTarget.value = null
  isOptimizingHarness.value = false
  optimizingAssistantIndex.value = null
  isHarnessModalOpen.value = false
  harnessResult.value = null
  harnessTarget.value = null
  isAuditingConversation.value = false
  isConversationAuditModalOpen.value = false
  conversationAuditResult.value = null
  harnessSuccessToast.value = null
}

// =========================================================================
// HARNESS² HOOK FORMATTERS & RECURSIVE OPTIMIZATION METHODS
// =========================================================================

const formatHookLabel = (hookCode) => {
  const map = {
    HOOK_CLOSING_QUESTION_CONTROL: '🛡️ Chốt câu hỏi',
    HOOK_CLOSING_QUESTION: '🛡️ Chốt câu hỏi',
    CLOSING_QUESTION_CONTROL: '🛡️ Chốt câu hỏi',
    HOOK_ANTI_INFO_DUMPING_DISCOVERY: '🛡️ Chống info-dump',
    HOOK_ANTI_INFO_DUMPING: '🛡️ Chống info-dump',
    ANTI_INFO_DUMPING_DISCOVERY: '🛡️ Chống info-dump',
    HOOK_HONORIFIC_CONSISTENCY: '🛡️ Danh xưng',
    HONORIFIC_CONSISTENCY: '🛡️ Danh xưng',
    HOOK_BUBBLE_CAP: '🛡️ Giới hạn bong bóng',
    BUBBLE_CAP: '🛡️ Giới hạn bong bóng'
  }
  return map[hookCode] || `🛡️ ${String(hookCode).replace(/^HOOK_/, '')}`
}

const getHookTooltip = (hookCode) => {
  const map = {
    HOOK_CLOSING_QUESTION_CONTROL: 'In-loop Guardrail (<5ms): Triệt tiêu câu hỏi đóng xin phép ("không ạ?"), ép kết thúc bằng câu hỏi mở A/B.',
    HOOK_CLOSING_QUESTION: 'In-loop Guardrail (<5ms): Triệt tiêu câu hỏi đóng xin phép ("không ạ?"), ép kết thúc bằng câu hỏi mở A/B.',
    CLOSING_QUESTION_CONTROL: 'In-loop Guardrail (<5ms): Triệt tiêu câu hỏi đóng xin phép ("không ạ?"), ép kết thúc bằng câu hỏi mở A/B.',
    HOOK_ANTI_INFO_DUMPING_DISCOVERY: 'In-loop Guardrail (<5ms): Chống xả sạch thông tin giá ở bước DISCOVERY; giữ 1 giá khởi điểm + 1 quyền lợi + 1 câu hỏi dẫn dắt.',
    HOOK_ANTI_INFO_DUMPING: 'In-loop Guardrail (<5ms): Chống xả sạch thông tin giá ở bước DISCOVERY; giữ 1 giá khởi điểm + 1 quyền lợi + 1 câu hỏi dẫn dắt.',
    ANTI_INFO_DUMPING_DISCOVERY: 'In-loop Guardrail (<5ms): Chống xả sạch thông tin giá ở bước DISCOVERY; giữ 1 giá khởi điểm + 1 quyền lợi + 1 câu hỏi dẫn dắt.',
    HOOK_HONORIFIC_CONSISTENCY: 'In-loop Guardrail (<5ms): Sửa triệt để rò rỉ đại từ (Chị -> Anh) trong <5ms, bảo đảm 100% nhất quán.',
    HONORIFIC_CONSISTENCY: 'In-loop Guardrail (<5ms): Sửa triệt để rò rỉ đại từ (Chị -> Anh) trong <5ms, bảo đảm 100% nhất quán.',
    HOOK_BUBBLE_CAP: 'In-loop Guardrail (<5ms): Giới hạn tối đa <= 2 bong bóng ngắn, tự động gom lời chào Turn 1 vào câu hỏi chính.',
    BUBBLE_CAP: 'In-loop Guardrail (<5ms): Giới hạn tối đa <= 2 bong bóng ngắn, tự động gom lời chào Turn 1 vào câu hỏi chính.'
  }
  return map[hookCode] || 'In-loop Deterministic Guardrail Hook chạy <5ms trước khi gửi phản hồi.'
}

const formatDeltaScore = (delta) => {
  if (delta === null || delta === undefined) return '0.00'
  const num = Number(delta)
  return num > 0 ? `+${num.toFixed(2)}` : num.toFixed(2)
}

const splitTextBubbles = (text) => {
  if (!text) return []
  return text.split(/\n{2,}/).map(t => t.trim()).filter(Boolean)
}

const runHarnessOptimization = async (assistantIdx) => {
  if (isOptimizingHarness.value) return

  let turnStartIdx = assistantIdx
  while (turnStartIdx > 0 && chatItems.value[turnStartIdx - 1]?.role === 'assistant') {
    turnStartIdx--
  }
  let turnEndIdx = assistantIdx
  while (turnEndIdx + 1 < chatItems.value.length && chatItems.value[turnEndIdx + 1]?.role === 'assistant') {
    turnEndIdx++
  }

  let customerMsg = ''
  let questionIdx = -1
  for (let i = turnStartIdx - 1; i >= 0; i--) {
    if (chatItems.value[i].role === 'user') {
      customerMsg = chatItems.value[i].text
      questionIdx = i
      break
    }
  }
  if (!customerMsg) {
    customerMsg = 'Khách hàng nhắn tin hỏi tư vấn trên Fanpage'
  }

  const baseResp = chatItems.value
    .slice(turnStartIdx, turnEndIdx + 1)
    .map(item => item.text || '')
    .filter(Boolean)
    .join('\n\n')

  const priorHistory = questionIdx > 0
    ? buildTurnHistoryFromChatItems(chatItems.value.slice(0, questionIdx))
    : []

  const currentTriggeredHooks = chatItems.value[turnEndIdx]?.triggeredHooks || []
  const tempDelta = currentInsight.value?.temperature_delta ?? 0

  harnessTarget.value = {
    assistantIndex: assistantIdx,
    turnStartIndex: turnStartIdx,
    turnEndIndex: turnEndIdx,
    customerMessage: customerMsg,
    baseResponse: baseResp
  }

  isOptimizingHarness.value = true
  optimizingAssistantIndex.value = assistantIdx

  try {
    const result = await api.harness.optimize({
      pageId: props.activePage?.id || null,
      customerMessage: customerMsg,
      baseResponse: baseResp,
      temperatureDelta: tempDelta,
      history: priorHistory.map(h => `${h.role}: ${h.content}`),
      triggeredHooks: currentTriggeredHooks
    })

    harnessResult.value = result
    isHarnessModalOpen.value = true
  } catch (err) {
    console.error('[Harness] Optimization error:', err)
    showHarnessToast(`Lỗi khi chạy tối ưu Harness²: ${err.message || err}`, 'error')
  } finally {
    isOptimizingHarness.value = false
    optimizingAssistantIndex.value = null
  }
}

const closeHarnessModal = () => {
  if (harnessResult.value?.verdict === 'KEEP_BASE') {
    showHarnessToast('Phản hồi ban đầu đã đạt chuẩn tối ưu cao nhất (KEEP_BASE). Giữ nguyên thư viện kinh nghiệm để chống nhiễu.', 'info')
  }
  isHarnessModalOpen.value = false
  harnessResult.value = null
  harnessTarget.value = null
}

const applyHarnessWinningReply = () => {
  if (!harnessResult.value || !harnessTarget.value) return
  if (harnessResult.value.verdict !== 'ADOPTED') {
    closeHarnessModal()
    return
  }

  const { turnStartIndex: startIdx, turnEndIndex: endIdx } = harnessTarget.value
  const winningBubbles = harnessResult.value.winning_bubbles && harnessResult.value.winning_bubbles.length > 0
    ? harnessResult.value.winning_bubbles
    : splitTextBubbles(harnessResult.value.improved_response)

  const existingSkills = chatItems.value[endIdx]?.activeSkills || []
  const existingRules = chatItems.value[endIdx]?.appliedRules || []
  const newRule = harnessResult.value.new_experience_rule
  const mergedRules = newRule ? [...existingRules, newRule] : existingRules

  const replacementItems = winningBubbles.map((text, bIdx) => ({
    role: 'assistant',
    text: text,
    isHandoff: false,
    isCorrected: true,
    isOptimized: true,
    optimizedRunId: harnessResult.value.run_id || harnessResult.value.runId,
    appliedRules: bIdx === winningBubbles.length - 1 ? mergedRules : [],
    activeSkills: bIdx === winningBubbles.length - 1 ? existingSkills : [],
    triggeredHooks: bIdx === winningBubbles.length - 1 ? ['HOOK_HONORIFIC_CONSISTENCY', 'HOOK_CLOSING_QUESTION_CONTROL'] : []
  }))

  chatItems.value.splice(startIdx, endIdx - startIdx + 1, ...replacementItems)
  conversationHistory.value = buildTurnHistoryFromChatItems(chatItems.value)

  const scoreText = harnessResult.value.delta_score ? ` (+${formatDeltaScore(harnessResult.value.delta_score)} điểm)` : ''
  showHarnessToast(`✨ Đã tự động nâng cấp phản hồi với Harness²${scoreText} và đúc kết kinh nghiệm vào Playbook!`, 'success')

  isHarnessModalOpen.value = false
  harnessResult.value = null
  harnessTarget.value = null
}

const formatFindingCategory = (category, severity) => {
  const map = {
    REDUNDANT_TIME_QUESTION_ON_COMPLAINT: '🚨 Lỗi Nghiêm Trọng: Hỏi lặp giờ khi khách đã phàn nàn',
    POST_BOOKING_OVER_QUESTIONING: '⚠️ Thừa thãi: Hỏi thêm khung giờ sau khi đã chốt lịch & SĐT',
    CONTRADICTORY_TIME_SLOT_QUESTION: '⚠️ Mâu thuẫn: Hỏi Sáng/Chiều dù khách đã chốt khung giờ',
    UNGROUNDED_SYMPTOM_HALLUCINATION: '🚨 Lỗi Bịa Đặt: Tự suy diễn vị trí bệnh lý khách không nêu',
    CONTEXT_RESET_ON_MALE_DECLARATION: '🚨 Đứt gãy ngữ cảnh & Sai danh xưng khi khách báo là Nam',
    WEAK_PRICE_COMPARISON_DEFENSE: '💡 Cần nâng cấp: Phản biện so sánh giá chưa neo giá trị',
    REPETITIVE_TEMPLATE_PHRASING: '💡 Cần nâng cấp: Lặp lại văn mẫu máy móc',
    HONORIFIC_DRIFT: '🚨 Lỗi Danh xưng: Rò rỉ Chị/Anh không đồng nhất',
    OPTIMAL: '✅ Đạt chuẩn tối ưu'
  }
  return map[category] || (severity === 'OPTIMAL' ? '✅ Đạt chuẩn tối ưu' : `🔍 ${category}`)
}

const buildAllTurnsForAudit = () => {
  const turns = []
  for (const item of chatItems.value) {
    if (!item || !item.text || item.role === 'timeline') continue
    const last = turns[turns.length - 1]
    if (last && last.role === item.role) {
      last.content = `${last.content}\n\n${item.text}`
    } else {
      turns.push({ role: item.role, content: item.text })
    }
  }
  return turns
}

const normalizeConversationAuditResult = (raw) => {
  if (!raw) return null
  const rawFindings = raw.turn_findings || raw.turnFindings || raw.findings || []
  const normalizedFindings = rawFindings.map((f, idx) => {
    const status = f.status || f.severity || 'OPTIMAL'
    const isOptimal = status === 'OPTIMAL'
    const category = f.issue_type || f.issueType || f.issue_category || (isOptimal ? 'OPTIMAL' : 'NEEDS_IMPROVEMENT')
    return {
      ...f,
      exchange_index: f.exchange_index ?? f.exchangeIndex ?? (idx + 1),
      customer_message: f.customer_message ?? f.customerMessage ?? '',
      severity: isOptimal ? 'OPTIMAL' : (category.includes('COMPLAINT') || category.includes('HONORIFIC') || category.includes('HALLUCINATION') || category.includes('RESET') ? 'CRITICAL' : 'HIGH'),
      status,
      issue_category: category,
      issue_title: f.issue_title ?? f.issueTitle ?? null,
      issue_summary: f.what_went_wrong ?? f.whatWentWrong ?? f.issue_summary ?? '',
      recommended_fix: f.how_to_fix ?? f.howToFix ?? f.recommended_fix ?? '',
      original_reply: f.original_bot_reply ?? f.originalBotReply ?? f.original_reply ?? '',
      improved_reply: f.rewritten_bot_reply ?? f.rewrittenBotReply ?? f.improved_reply ?? '',
      improved_bubbles: f.rewritten_bubbles ?? f.rewrittenBubbles ?? f.improved_bubbles ?? []
    }
  })
  return {
    ...raw,
    total_exchanges: raw.total_exchanges ?? raw.totalExchanges ?? normalizedFindings.length,
    issues_found_count: raw.issues_found_count ?? raw.issuesFoundCount ?? normalizedFindings.filter(f => f.severity !== 'OPTIMAL').length,
    overall_score_before: raw.overall_score_before ?? raw.overallScoreBefore ?? 0.65,
    overall_score_after: raw.overall_score_after ?? raw.overallScoreAfter ?? 0.96,
    executive_summary: raw.executive_summary ?? raw.executiveSummary ?? '',
    state_summary: raw.state_summary ?? raw.stateSummary ?? null,
    learned_rules: raw.learned_rules ?? raw.learnedRules ?? [],
    findings: normalizedFindings
  }
}

const runWholeConversationAudit = async () => {
  if (isAuditingConversation.value || chatItems.value.length === 0) return

  const turns = buildAllTurnsForAudit()
  if (turns.length === 0) return

  isAuditingConversation.value = true
  try {
    const result = await api.harness.auditConversation({
      pageId: props.activePage?.id || null,
      turns,
      autoApplyRules: true
    })

    conversationAuditResult.value = normalizeConversationAuditResult(result)
    isConversationAuditModalOpen.value = true
  } catch (err) {
    console.error('[Harness] Conversation audit error:', err)
    showHarnessToast(`Lỗi khi kiểm toán toàn hội thoại: ${err.message || err}`, 'error')
  } finally {
    isAuditingConversation.value = false
  }
}

const switchToWholeConversationAudit = async () => {
  isHarnessModalOpen.value = false
  await runWholeConversationAudit()
}

const closeConversationAuditModal = () => {
  isConversationAuditModalOpen.value = false
}

const findAssistantSliceForExchange = (exchangeIndex) => {
  let currentExchange = 0
  let idx = 0
  while (idx < chatItems.value.length) {
    const item = chatItems.value[idx]
    if (item.role === 'timeline') {
      idx++
      continue
    }
    if (item.role === 'user') {
      while (idx < chatItems.value.length && chatItems.value[idx].role === 'user') {
        idx++
      }
      while (idx < chatItems.value.length && chatItems.value[idx].role === 'timeline') {
        idx++
      }
      if (idx < chatItems.value.length && chatItems.value[idx].role === 'assistant') {
        const startIdx = idx
        while (idx + 1 < chatItems.value.length && chatItems.value[idx + 1].role === 'assistant') {
          idx++
        }
        const endIdx = idx
        currentExchange++
        if (currentExchange === exchangeIndex) {
          return { startIdx, endIdx }
        }
      }
    } else if (item.role === 'assistant') {
      const startIdx = idx
      while (idx + 1 < chatItems.value.length && chatItems.value[idx + 1].role === 'assistant') {
        idx++
      }
      const endIdx = idx
      currentExchange++
      if (currentExchange === exchangeIndex) {
        return { startIdx, endIdx }
      }
    }
    idx++
  }
  return null
}

const applySingleTurnAuditFix = (finding) => {
  if (!finding || finding.severity === 'OPTIMAL') return
  const slice = findAssistantSliceForExchange(finding.exchange_index)
  if (!slice) return

  const { startIdx, endIdx } = slice
  const winningBubbles = finding.improved_bubbles && finding.improved_bubbles.length > 0
    ? finding.improved_bubbles
    : splitTextBubbles(finding.improved_reply)

  const existingSkills = chatItems.value[endIdx]?.activeSkills || []
  const existingRules = chatItems.value[endIdx]?.appliedRules || []
  const learnedRules = conversationAuditResult.value?.learned_rules || []
  const mergedRules = learnedRules.length > 0 ? [...existingRules, ...learnedRules.slice(0, 1)] : existingRules

  const replacementItems = winningBubbles.map((text, bIdx) => ({
    role: 'assistant',
    text,
    isHandoff: false,
    isCorrected: true,
    isOptimized: true,
    appliedRules: bIdx === winningBubbles.length - 1 ? mergedRules : [],
    activeSkills: bIdx === winningBubbles.length - 1 ? existingSkills : [],
    triggeredHooks: bIdx === winningBubbles.length - 1 ? ['HOOK_HONORIFIC_CONSISTENCY', 'HOOK_CLOSING_QUESTION_CONTROL'] : []
  }))

  chatItems.value.splice(startIdx, endIdx - startIdx + 1, ...replacementItems)
  conversationHistory.value = buildTurnHistoryFromChatItems(chatItems.value)
  finding.severity = 'OPTIMAL'
  finding.original_reply = finding.improved_reply
  if (conversationAuditResult.value && conversationAuditResult.value.issues_found_count > 0) {
    conversationAuditResult.value.issues_found_count--
  }
  showHarnessToast(`✨ Đã tối ưu Lượt #${finding.exchange_index} theo ngữ cảnh toàn hội thoại!`, 'success')
}

const applyAllConversationAuditFixes = () => {
  if (!conversationAuditResult.value?.findings) return
  const faultyFindings = [...conversationAuditResult.value.findings]
    .filter(f => f.severity !== 'OPTIMAL')
    .sort((a, b) => b.exchange_index - a.exchange_index)

  let fixedCount = 0
  for (const finding of faultyFindings) {
    const slice = findAssistantSliceForExchange(finding.exchange_index)
    if (!slice) continue
    const { startIdx, endIdx } = slice
    const winningBubbles = finding.improved_bubbles && finding.improved_bubbles.length > 0
      ? finding.improved_bubbles
      : splitTextBubbles(finding.improved_reply)

    const existingSkills = chatItems.value[endIdx]?.activeSkills || []
    const existingRules = chatItems.value[endIdx]?.appliedRules || []
    const learnedRules = conversationAuditResult.value?.learned_rules || []
    const mergedRules = learnedRules.length > 0 ? [...existingRules, ...learnedRules.slice(0, 1)] : existingRules

    const replacementItems = winningBubbles.map((text, bIdx) => ({
      role: 'assistant',
      text,
      isHandoff: false,
      isCorrected: true,
      isOptimized: true,
      appliedRules: bIdx === winningBubbles.length - 1 ? mergedRules : [],
      activeSkills: bIdx === winningBubbles.length - 1 ? existingSkills : [],
      triggeredHooks: bIdx === winningBubbles.length - 1 ? ['HOOK_HONORIFIC_CONSISTENCY', 'HOOK_CLOSING_QUESTION_CONTROL'] : []
    }))

    chatItems.value.splice(startIdx, endIdx - startIdx + 1, ...replacementItems)
    finding.severity = 'OPTIMAL'
    finding.original_reply = finding.improved_reply
    fixedCount++
  }

  conversationHistory.value = buildTurnHistoryFromChatItems(chatItems.value)
  conversationAuditResult.value.issues_found_count = 0
  isConversationAuditModalOpen.value = false
  showHarnessToast(`✨ Đã áp dụng sửa toàn bộ ${fixedCount} lượt chưa tối ưu và lưu quy tắc vào Playbook!`, 'success')
}

onBeforeUnmount(() => {
  if (burstDebounceTimer) {
    clearTimeout(burstDebounceTimer)
    burstDebounceTimer = null
  }
  if (harnessToastTimer) {
    clearTimeout(harnessToastTimer)
    harnessToastTimer = null
  }
})
</script>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(8px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.fb-sim-modal {
  width: 100%;
  max-width: 1140px;
  height: 88vh;
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 24px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.18), 0 4px 16px rgba(15, 23, 42, 0.06);
}

.fb-sim-header {
  padding: 16px 22px;
  background: #F8FAFD;
  border-bottom: 1px solid #DDE3EA;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.fb-sim-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.fb-icon-badge {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(135deg, #0B57D0, #1A73E8);
  color: #FFFFFF;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.2rem;
  box-shadow: 0 3px 10px rgba(11, 87, 208, 0.22);
}

.fb-sim-title {
  font-family: var(--font-display);
  font-size: 1.02rem;
  font-weight: 700;
  color: #1F1F1F;
  display: flex;
  align-items: center;
  gap: 10px;
}

.api-pill {
  font-size: 0.7rem;
  font-family: var(--font-mono);
  background: #E6F4EA;
  color: #137333;
  border: 1px solid #A8DAB5;
  padding: 2px 8px;
  border-radius: 999px;
  font-weight: 600;
}

.fb-sim-subtitle {
  font-size: 0.76rem;
  color: #444746;
  margin-top: 2px;
}

.btn-close-modal {
  background: none;
  border: none;
  color: #747775;
  font-size: 1.5rem;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 999px;
}

.btn-close-modal:hover {
  background: #EDF2FA;
  color: #1F1F1F;
}

.fb-sim-body {
  flex: 1;
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  min-height: 0;
}

/* Left Messenger Pane */
.messenger-pane {
  display: flex;
  flex-direction: column;
  border-right: 1px solid #DDE3EA;
  background: #FFFFFF;
  min-height: 0;
}

.messenger-topbar {
  padding: 12px 16px;
  background: #FFFFFF;
  border-bottom: 1px solid #DDE3EA;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.messenger-topbar.is-handoff {
  background: #FEF7E0;
  border-bottom-color: #FDE293;
}

.page-avatar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(135deg, #0B57D0, #1A73E8);
  color: #fff;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.page-display-name {
  font-size: 0.9rem;
  font-weight: 700;
  color: #1F1F1F;
}

.bot-live-status {
  font-size: 0.74rem;
  color: #444746;
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot-active {
  background: #137333;
}

.dot-handoff {
  background: #EA8600;
}

.handoff-status-text {
  color: #B06000;
  font-weight: 600;
}

.btn-reset-sim {
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  color: #444746;
  font-size: 0.74rem;
  font-weight: 600;
  padding: 5px 12px;
  border-radius: 999px;
  cursor: pointer;
}

.btn-reset-sim:hover {
  background: #EDF2FA;
  color: #1F1F1F;
}

.handoff-alert-banner {
  background: #FEF7E0;
  border-bottom: 1px solid #FDE293;
  padding: 10px 16px;
  font-size: 0.78rem;
  color: #8F4D00;
}

.handoff-alert-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.handoff-cat-badge {
  background: #FDE293;
  color: #7A4100;
  font-size: 0.68rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}

.handoff-reason-text {
  color: #1F1F1F;
  margin-bottom: 6px;
}

.btn-resume-bot {
  background: #E6F4EA;
  border: 1px solid #A8DAB5;
  color: #137333;
  font-size: 0.72rem;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 999px;
  cursor: pointer;
}

.messenger-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: #F8FAFD;
}

.messenger-empty {
  margin: auto;
  text-align: center;
  max-width: 380px;
  color: #444746;
  font-size: 0.84rem;
  line-height: 1.5;
}

.msg-bubble-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.msg-bubble-row.is-customer {
  justify-content: flex-end;
}

.msg-bubble-row.is-page {
  justify-content: flex-start;
}

.mini-page-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: linear-gradient(135deg, #0B57D0, #1A73E8);
  color: #fff;
  font-size: 0.72rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.assistant-bubble-col {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 82%;
}

.assistant-bubble-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-left: 2px;
}

.applied-rules-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  background: #E8F0FE;
  border: 1px solid #A8C7FA;
  border-radius: 999px;
  font-size: 0.72rem;
  color: #041E49;
}

.badge-icon {
  font-size: 0.75rem;
}

.badge-label {
  font-weight: 600;
  color: #0B57D0;
}

.rule-chip {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 0.7rem;
  color: #1F1F1F;
}

.btn-teach-fb {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  color: #444746;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 0.72rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.btn-teach-fb:hover {
  background: #FEF7E0;
  border-color: #FDE293;
  color: #B06000;
}

.msg-bubble {
  padding: 10px 14px;
  border-radius: 18px;
  font-size: 0.86rem;
  line-height: 1.48;
  white-space: pre-wrap;
}

.msg-bubble.user {
  max-width: 78%;
  background: #0B57D0;
  color: #ffffff;
  border-bottom-right-radius: 4px;
}

.msg-bubble.assistant {
  background: #FFFFFF;
  color: #1F1F1F;
  border: 1px solid #DDE3EA;
  border-bottom-left-radius: 4px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.msg-bubble.handoff-bubble {
  border-color: #FDE293;
  background: #FEF7E0;
}

.msg-bubble.is-corrected {
  border-color: #A8DAB5;
  box-shadow: 0 0 0 2px rgba(19, 115, 51, 0.12);
}

.typing-indicator {
  display: inline-flex;
  gap: 4px;
  padding: 12px 16px;
}

.typing-indicator span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #0B57D0;
  animation: fbBounce 1.2s infinite;
}

.typing-indicator span:nth-child(2) {
  animation-delay: 0.2s;
}
.typing-indicator span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes fbBounce {
  0%, 80%, 100% { transform: translateY(0); opacity: 0.4; }
  40% { transform: translateY(-4px); opacity: 1; }
}

.idle-simulation-bar {
  padding: 7px 14px;
  background: #F0F4F9;
  border-bottom: 1px solid #DDE3EA;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.idle-bar-label {
  font-size: 0.72rem;
  color: #444746;
  font-weight: 600;
}

.idle-btn-group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-idle-sim {
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 0.72rem;
  font-weight: 600;
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  color: #0B57D0;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-idle-sim:hover:not(:disabled) {
  background: #D3E3FD;
  border-color: #A8C7FA;
  color: #041E49;
}

.btn-idle-sim:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.timeline-row {
  display: flex;
  justify-content: center;
  margin: 10px 0;
}

.timeline-divider-badge {
  font-size: 0.71rem;
  font-style: italic;
  color: #444746;
  background: #FFFFFF;
  border: 1px dashed #C4CDD5;
  padding: 4px 14px;
  border-radius: 999px;
}

.messenger-input-bar {
  padding: 10px 14px;
  border-top: 1px solid #DDE3EA;
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: #FFFFFF;
}

.burst-typing-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px;
  background: #E8F0FE;
  border: 1px solid #A8C7FA;
  border-radius: 999px;
  font-size: 0.73rem;
  color: #041E49;
}

.burst-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #0B57D0;
  animation: burstPulse 1s infinite ease-in-out;
}

@keyframes burstPulse {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.4); opacity: 0.45; }
}

.messenger-input-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.messenger-input {
  flex: 1;
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  border-radius: 999px;
  padding: 9px 16px;
  color: #1F1F1F;
  font-size: 0.85rem;
  outline: none;
}

.messenger-input:focus {
  background: #FFFFFF;
  border-color: #0B57D0;
  box-shadow: 0 0 0 3px rgba(11, 87, 208, 0.12);
}

.btn-send-fb {
  background: #0B57D0;
  color: #ffffff;
  border: none;
  border-radius: 999px;
  padding: 0 18px;
  font-weight: 600;
  font-size: 0.84rem;
  cursor: pointer;
}

.btn-send-fb:hover:not(:disabled) {
  background: #0842A0;
}

.btn-send-fb:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* Right Telemetry & Scenarios Pane */
.telemetry-pane {
  padding: 16px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: #F0F4F9;
}

.telemetry-card {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 16px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.telemetry-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 0.78rem;
  font-weight: 700;
  color: #1F1F1F;
}

.conf-badge {
  background: #E8F0FE;
  color: #0B57D0;
  border: 1px solid #D3E3FD;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 0.7rem;
}

.stage-stepper {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
}

.step-item {
  font-size: 0.72rem;
  padding: 6px 8px;
  border-radius: 8px;
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  color: #747775;
}

.step-item.active {
  background: #E6F4EA;
  border-color: #A8DAB5;
  color: #137333;
  font-weight: 700;
}

.step-item.step-handoff.active {
  background: #FEF7E0;
  border-color: #FDE293;
  color: #B06000;
}

.lead-capture-box {
  background: #F8FAFD;
  border: 1px dashed #C4CDD5;
  border-radius: 10px;
  padding: 8px 10px;
}

.lead-capture-box.has-lead {
  border-style: solid;
  border-color: #A8DAB5;
  background: #E6F4EA;
}

.lead-box-title {
  font-size: 0.72rem;
  font-weight: 700;
  color: #1F1F1F;
  margin-bottom: 4px;
}

.lead-details {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.lead-pill {
  background: #FFFFFF;
  color: #137333;
  border: 1px solid #A8DAB5;
  font-size: 0.72rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}

.lead-note {
  font-size: 0.72rem;
  color: #1F1F1F;
}

.lead-empty {
  font-size: 0.72rem;
  color: #747775;
}

.scenarios-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.scenarios-title {
  font-size: 0.76rem;
  font-weight: 700;
  color: #444746;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.scenarios-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sc-btn {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 12px;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s ease;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sc-btn:hover:not(:disabled) {
  border-color: #A8C7FA;
  background: #F8FAFD;
  transform: translateY(-1px);
  box-shadow: 0 3px 8px rgba(11, 87, 208, 0.08);
}

.sc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sc-badge {
  font-size: 0.7rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}

.sc-badge.normal {
  background: #E8F0FE;
  color: #0B57D0;
}

.sc-badge.lead {
  background: #E6F4EA;
  color: #137333;
}

.sc-badge.handoff {
  background: #FEF7E0;
  color: #B06000;
}

.sc-try {
  font-size: 0.68rem;
  color: #0B57D0;
  font-weight: 600;
}

.sc-msg {
  font-size: 0.78rem;
  color: #1F1F1F;
  font-weight: 500;
  line-height: 1.35;
}

.sc-expect {
  font-size: 0.68rem;
  color: #747775;
  line-height: 1.3;
}

.messenger-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-skill-sim {
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 0.74rem;
  font-weight: 600;
  border: 1px solid #D0BCFF;
  background: #F3EDF7;
  color: #4A148C;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-skill-sim:hover {
  background: #E8DDF8;
  color: #311B92;
}

.active-skills-badge {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: 999px;
  background: #F3EDF7;
  border: 1px solid #D0BCFF;
  font-size: 0.7rem;
}

.skill-chip-mini {
  padding: 1px 6px;
  border-radius: 999px;
  background: #FFFFFF;
  border: 1px solid #D0BCFF;
  color: #4A148C;
  font-weight: 600;
}

/* Customer Psychology & Insight Radar Card */
.insight-radar-card {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 16px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.insight-radar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.insight-title-row {
  font-size: 0.8rem;
  font-weight: 700;
  color: #1F1F1F;
}

.honorific-pill {
  font-size: 0.7rem;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  background: #E8F0FE;
  color: #0B57D0;
  border: 1px solid #D3E3FD;
}

.insight-empty {
  font-size: 0.74rem;
  color: #747775;
  line-height: 1.4;
  padding: 6px 0;
}

.insight-body {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.insight-top-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: 8px;
}

.archetype-box,
.temp-box {
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  border-radius: 10px;
  padding: 7px 9px;
}

.insight-sub-label {
  font-size: 0.69rem;
  font-weight: 700;
  color: #747775;
  display: block;
  margin-bottom: 3px;
}

.archetype-value {
  font-size: 0.76rem;
  font-weight: 700;
  color: #1F1F1F;
  line-height: 1.3;
}

.temp-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 5px;
}

.temp-sub-title-group {
  display: flex;
  align-items: center;
  gap: 5px;
}

.prev-temp-sub {
  font-size: 0.68rem;
  color: #747775;
  font-weight: 600;
}

.temp-indicators-group {
  display: flex;
  align-items: center;
  gap: 5px;
}

.delta-indicator {
  font-size: 0.68rem;
  font-weight: 800;
  padding: 1px 6px;
  border-radius: 999px;
}

.delta-pos {
  background: #E6F4EA;
  color: #137333;
  border: 1px solid #A8DAB5;
}

.delta-neg {
  background: #FCE8E6;
  color: #C5221F;
  border: 1px solid #F6AEA9;
}

.delta-neutral {
  background: #F0F4F9;
  color: #444746;
}

.temp-badge {
  font-size: 0.68rem;
  font-weight: 800;
  padding: 1px 6px;
  border-radius: 999px;
}

.temp-hot {
  background: #FCE8E6;
  color: #C5221F;
}

.temp-warm {
  background: #FEF7E0;
  color: #B06000;
}

.temp-cold {
  background: #E8F0FE;
  color: #0B57D0;
}

.temp-bar-track {
  width: 100%;
  height: 7px;
  border-radius: 999px;
  background: #E1E6EC;
  overflow: hidden;
}

.temp-bar-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.35s ease;
}

.fill-hot {
  background: linear-gradient(90deg, #F9AB00, #D93025);
}

.fill-warm {
  background: linear-gradient(90deg, #1A73E8, #F9AB00);
}

.fill-cold {
  background: linear-gradient(90deg, #9AA0A6, #1A73E8);
}

.momentum-meta-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  margin-top: 6px;
}

.momentum-chip {
  font-size: 0.67rem;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
  line-height: 1.3;
}

.momentum-rising {
  background: #FCE8E6;
  color: #C5221F;
  border: 1px solid #F6AEA9;
}

.momentum-stable {
  background: #E8F0FE;
  color: #0B57D0;
  border: 1px solid #A8C7FA;
}

.momentum-cooling_half_hearted {
  background: #FEF7E0;
  color: #B06000;
  border: 1px solid #FDE293;
}

.momentum-cooling_idle {
  background: #F0F4F9;
  color: #444746;
  border: 1px solid #DDE3EA;
}

.momentum-recovering {
  background: #E6F4EA;
  color: #137333;
  border: 1px solid #A8DAB5;
}

.decay-reason-chip {
  font-size: 0.66rem;
  color: #747775;
  font-style: italic;
}

.insight-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.insight-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.insight-chip {
  font-size: 0.7rem;
  padding: 3px 8px;
  border-radius: 999px;
  line-height: 1.3;
}

.chip-pain {
  background: #E8F0FE;
  color: #041E49;
  border: 1px solid #D3E3FD;
}

.chip-concern {
  background: #FEF7E0;
  color: #8F4D00;
  border: 1px solid #FDE293;
}

.chip-skill {
  background: #F3EDF7;
  color: #4A148C;
  border: 1px solid #D0BCFF;
  font-weight: 600;
}

.strategy-callout {
  background: #E6F4EA;
  border: 1px solid #A8DAB5;
  border-radius: 10px;
  padding: 8px 10px;
}

.strategy-title {
  font-size: 0.7rem;
  font-weight: 700;
  color: #137333;
  display: block;
  margin-bottom: 2px;
}

.strategy-text {
  margin: 0;
  font-size: 0.73rem;
  color: #0D5023;
  line-height: 1.38;
}

.skills-row-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.btn-link-mini {
  background: none;
  border: none;
  color: #0B57D0;
  font-size: 0.69rem;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}

.btn-link-mini:hover {
  text-decoration: underline;
  color: #0842A0;
}

/* =========================================================================
   IN-LOOP DETERMINISTIC GUARDRAIL HOOKS BADGE (<5ms)
   ========================================================================= */

.triggered-hooks-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  background: #F3E8FD;
  border: 1px solid #D8B4FE;
  border-radius: 999px;
  font-size: 0.72rem;
  color: #581C87;
  box-shadow: 0 1px 2px rgba(88, 28, 135, 0.04);
}

.triggered-hooks-badge .badge-label {
  font-weight: 700;
  color: #6B21A8;
}

.hook-chip {
  background: #FFFFFF;
  border: 1px solid #E9D5FF;
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 0.7rem;
  font-weight: 600;
  color: #581C87;
  cursor: help;
  transition: all 0.15s ease;
}

.hook-chip:hover {
  background: #FAF5FF;
  border-color: #C084FC;
  transform: translateY(-1px);
}

/* =========================================================================
   ACTIONS GROUP & 1-CLICK HARNESS² OPTIMIZER BUTTON
   ========================================================================= */

.assistant-actions-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.btn-harness-opt {
  background: #FAF5FF;
  border: 1px solid #E9D5FF;
  color: #7E22CE;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 0.72rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  box-shadow: 0 1px 2px rgba(126, 34, 206, 0.05);
}

.btn-harness-opt:hover:not(:disabled) {
  background: #F3E8FD;
  border-color: #D8B4FE;
  color: #6B21A8;
  box-shadow: 0 2px 6px rgba(126, 34, 206, 0.15);
  transform: translateY(-1px);
}

.btn-harness-opt:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.btn-harness-opt.is-loading {
  background: #F3E8FD;
  border-color: #C084FC;
}

.spin-icon {
  display: inline-block;
  animation: spinSlow 2s linear infinite;
}

@keyframes spinSlow {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.msg-bubble.is-optimized {
  border-color: #C084FC;
  box-shadow: 0 0 0 2px rgba(192, 132, 252, 0.15);
}

.bubble-optimized-pill {
  font-size: 0.65rem;
  font-weight: 700;
  color: #7E22CE;
  background: #F3E8FD;
  padding: 1px 6px;
  border-radius: 4px;
  margin-bottom: 4px;
  display: inline-block;
}

/* =========================================================================
   HARNESS² COMPARISON MODAL DIALOG
   ========================================================================= */

.harness-modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.6);
  backdrop-filter: blur(8px);
  z-index: 1050;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.harness-modal-dialog {
  width: 100%;
  max-width: 880px;
  max-height: 90vh;
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 20px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.22);
}

.harness-modal-header {
  padding: 14px 20px;
  background: #F8FAFD;
  border-bottom: 1px solid #DDE3EA;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.harness-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.harness-avatar-badge {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  background: linear-gradient(135deg, #7E22CE, #9333EA);
  color: #FFFFFF;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.15rem;
  box-shadow: 0 3px 10px rgba(126, 34, 206, 0.25);
}

.harness-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.harness-title {
  font-family: inherit;
  font-size: 0.98rem;
  font-weight: 700;
  color: #1F1F1F;
  margin: 0;
}

.dim-pill {
  font-size: 0.68rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}

.dim-pill.INV {
  background: #F3E8FD;
  color: #581C87;
  border: 1px solid #D8B4FE;
}

.dim-pill.DOM {
  background: #E8F0FE;
  color: #041E49;
  border: 1px solid #A8C7FA;
}

.run-id-pill {
  font-size: 0.68rem;
  font-family: monospace;
  background: #F1F3F4;
  color: #444746;
  padding: 2px 6px;
  border-radius: 4px;
}

.harness-subtitle {
  font-size: 0.74rem;
  color: #444746;
  margin: 2px 0 0;
}

/* Pipeline Stepper */
.harness-pipeline-bar {
  padding: 10px 20px;
  background: #FFFFFF;
  border-bottom: 1px solid #EDF2FA;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.pipe-step {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.74rem;
  color: #747775;
}

.pipe-step .step-num {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #EDF2FA;
  color: #444746;
  font-size: 0.65rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.pipe-step.done {
  color: #1F1F1F;
  font-weight: 600;
}

.pipe-step.done .step-num {
  background: #E8F0FE;
  color: #0B57D0;
}

.pipe-step.step-winner .step-num {
  background: #CEEAD6;
  color: #0D652D;
}

.pipe-step.step-keep .step-num {
  background: #FDE293;
  color: #594300;
}

.pipe-connector {
  flex: 1;
  height: 2px;
  background: #EDF2FA;
  margin: 0 8px;
}

.pipe-connector.done {
  background: #C2E7FF;
}

/* Body & Verdict */
.harness-modal-body {
  padding: 18px 20px;
  overflow-y: auto;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.verdict-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 12px;
}

.verdict-banner.is-adopted {
  background: #E6F4EA;
  border: 1px solid #A8DAB5;
  color: #137333;
}

.verdict-banner.is-keep {
  background: #FEF7E0;
  border: 1px solid #FDE293;
  color: #B06000;
}

.verdict-icon {
  font-size: 1.5rem;
}

.verdict-headline {
  font-size: 0.88rem;
  font-weight: 700;
}

.verdict-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.08);
  margin-right: 4px;
}

.verdict-sub {
  font-size: 0.76rem;
  margin: 2px 0 0;
  opacity: 0.9;
}

/* Metrics Bar */
.metrics-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  padding: 10px 14px;
  border-radius: 12px;
}

.metric-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.m-label {
  font-size: 0.68rem;
  color: #747775;
  font-weight: 500;
}

.m-val {
  font-family: monospace;
  font-size: 0.92rem;
  font-weight: 700;
  color: #1F1F1F;
}

.m-val.candidate {
  color: #0B57D0;
}

.metric-card.highlight {
  margin-left: auto;
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  padding: 4px 10px;
  border-radius: 8px;
}

.m-val.delta.pos {
  color: #137333;
}

.m-val.delta.neg {
  color: #B06000;
}

.metric-arrow {
  color: #747775;
  font-size: 0.8rem;
}

/* Comparison Grid (Side by side) */
.comparison-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.candidate-card {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  border-radius: 14px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.candidate-card.is-winner {
  border-color: #137333;
  box-shadow: 0 0 0 2px rgba(19, 115, 51, 0.15);
}

.candidate-header {
  padding: 8px 12px;
  background: #F8FAFD;
  border-bottom: 1px solid #EDF2FA;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cand-name {
  font-size: 0.82rem;
  font-weight: 700;
  color: #1F1F1F;
}

.cand-label-revealed {
  font-size: 0.7rem;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 600;
  margin-left: 6px;
}

.cand-label-revealed.BASE {
  background: #F1F3F4;
  color: #444746;
}

.cand-label-revealed.CANDIDATE {
  background: #F3E8FD;
  color: #6B21A8;
}

.winner-badge {
  font-size: 0.68rem;
  font-weight: 700;
  color: #137333;
  background: #CEEAD6;
  padding: 2px 7px;
  border-radius: 999px;
}

.candidate-body {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
}

.cand-bubble {
  background: #F8FAFD;
  border: 1px solid #E1E3E1;
  padding: 8px 11px;
  border-radius: 12px;
  font-size: 0.8rem;
  line-height: 1.45;
  white-space: pre-wrap;
  color: #1F1F1F;
}

/* Rationale & Experience Card */
.rationale-box {
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  padding: 12px 14px;
  border-radius: 12px;
}

.rat-title {
  font-size: 0.75rem;
  font-weight: 700;
  color: #444746;
  margin-bottom: 4px;
}

.rat-text {
  font-size: 0.78rem;
  line-height: 1.45;
  color: #1F1F1F;
  margin: 0;
}

.experience-library-card {
  background: #FAF5FF;
  border: 1px solid #E9D5FF;
  border-radius: 12px;
  padding: 12px 14px;
}

.exp-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.exp-title {
  font-size: 0.78rem;
  font-weight: 700;
  color: #581C87;
}

.exp-dim-badge {
  margin-left: auto;
  font-size: 0.65rem;
  font-weight: 700;
  background: #E9D5FF;
  color: #581C87;
  padding: 1px 6px;
  border-radius: 4px;
}

.exp-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.exp-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.exp-tag {
  font-size: 0.68rem;
  font-weight: 700;
  color: #6B21A8;
}

.exp-val {
  font-size: 0.78rem;
  color: #1F1F1F;
  line-height: 1.4;
}

.exp-evidence-box {
  background: #FFFFFF;
  border: 1px solid #E9D5FF;
  border-radius: 6px;
  padding: 6px 8px;
  font-family: monospace;
  font-size: 0.72rem;
  white-space: pre-wrap;
  color: #374151;
  margin: 2px 0 0;
}

/* Footer Actions */
.harness-modal-footer {
  padding: 12px 20px;
  background: #F8FAFD;
  border-top: 1px solid #DDE3EA;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.btn-cancel-harness {
  background: #FFFFFF;
  border: 1px solid #DDE3EA;
  color: #444746;
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-cancel-harness:hover {
  background: #F1F3F4;
}

.btn-apply-harness {
  background: linear-gradient(135deg, #0B57D0, #1A73E8);
  border: none;
  color: #FFFFFF;
  padding: 6px 18px;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 2px 6px rgba(11, 87, 208, 0.25);
  transition: all 0.15s ease;
}

.btn-apply-harness:hover {
  background: linear-gradient(135deg, #0842A0, #1557B0);
  transform: translateY(-1px);
}

.btn-confirm-keep {
  background: #FEF7E0;
  border: 1px solid #FDE293;
  color: #B06000;
  padding: 6px 16px;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
}

/* Harness Toast Notification */
.harness-toast {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 1200;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 18px;
  border-radius: 12px;
  font-size: 0.82rem;
  font-weight: 600;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  animation: slideInToast 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}

.harness-toast.success {
  background: #E6F4EA;
  border: 1px solid #A8DAB5;
  color: #137333;
}

.harness-toast.info {
  background: #FEF7E0;
  border: 1px solid #FDE293;
  color: #B06000;
}

.harness-toast.error {
  background: #FCE8E6;
  border: 1px solid #FAD2CF;
  color: #C5221F;
}

@keyframes slideInToast {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.harness-fade-enter-active,
.harness-fade-leave-active {
  transition: opacity 0.3s ease, transform 0.3s ease;
}

.harness-fade-enter-from,
.harness-fade-leave-to {
  opacity: 0;
  transform: translateY(12px);
}

/* Whole-Conversation Audit Styles */
.btn-conv-audit-sim {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: linear-gradient(135deg, #0B57D0, #1A73E8);
  border: 1px solid #0B57D0;
  color: #FFFFFF;
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 0.74rem;
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 2px 6px rgba(11, 87, 208, 0.22);
  transition: all 0.15s ease;
}

.btn-conv-audit-sim:hover:not(:disabled) {
  background: linear-gradient(135deg, #0842A0, #1557B0);
  transform: translateY(-1px);
}

.btn-conv-audit-sim:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.btn-conv-audit-link {
  margin-right: auto;
  background: #E8F0FE;
  border: 1px solid #C2E7FF;
  color: #0B57D0;
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 0.76rem;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-conv-audit-link:hover {
  background: #D3E3FD;
}

.conv-audit-card {
  max-width: 980px;
}

.harness-modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(17, 24, 39, 0.45);
  backdrop-filter: blur(5px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2450;
  padding: 20px;
}

.harness-modal-card {
  width: 100%;
  max-width: 980px;
  max-height: 88vh;
  background: #FFFFFF;
  border-radius: 24px;
  border: 1px solid #DDE3EA;
  box-shadow: 0 24px 60px rgba(4, 30, 73, 0.18);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.harness-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.harness-logo-badge {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  background: linear-gradient(135deg, #0B57D0 0%, #6366F1 100%);
  color: #FFFFFF;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.3rem;
  flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(11, 87, 208, 0.25);
}

.harness-modal-title {
  margin: 0;
  font-size: 1.02rem;
  font-weight: 800;
  color: #1F1F1F;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.verdict-pill {
  font-size: 0.72rem;
  font-weight: 800;
  padding: 3px 10px;
  border-radius: 999px;
}

.verdict-pill.is-adopted {
  background: #E6F4EA;
  color: #137333;
  border: 1px solid #CEEAD6;
}

.verdict-pill.is-keep {
  background: #FEF7E0;
  color: #B06000;
  border: 1px solid #FDE293;
}

.harness-modal-subtitle {
  margin: 3px 0 0;
  font-size: 0.76rem;
  color: #444746;
}

.btn-close-harness {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 1px solid #DDE3EA;
  background: #FFFFFF;
  color: #444746;
  font-size: 1.25rem;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.15s ease;
}

.btn-close-harness:hover {
  background: #FCE8E6;
  color: #C5221F;
  border-color: #FAD2CF;
}

.harness-metrics-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #F8FAFD;
  border: 1px solid #DDE3EA;
  border-radius: 16px;
  padding: 12px 16px;
  flex-wrap: wrap;
}

.h-metric-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 12px;
  border-radius: 10px;
}

.h-metric-item.highlight {
  background: #E8F0FE;
  border: 1px solid #D3E3FD;
}

.h-m-label {
  font-size: 0.68rem;
  font-weight: 700;
  text-transform: uppercase;
  color: #747775;
}

.h-m-val {
  font-size: 1.05rem;
  font-weight: 800;
  color: #1F1F1F;
}

.h-m-val.delta-pos {
  color: #137333;
  font-size: 0.92rem;
}

.h-metric-arrow {
  color: #747775;
  font-weight: 700;
}

.candidates-comparison-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.candidate-col {
  border-radius: 14px;
  border: 1px solid #DDE3EA;
  background: #F8FAFD;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.candidate-col.is-winner {
  border: 2px solid #137333;
  background: #F4FBF6;
  box-shadow: 0 4px 14px rgba(19, 115, 51, 0.08);
}

.candidate-id-badge {
  font-size: 0.78rem;
  font-weight: 800;
  color: #041E49;
}

.conv-findings-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.conv-finding-item {
  border-radius: 14px;
  padding: 12px 14px;
  border: 1px solid #DDE3EA;
  background: #FFFFFF;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.conv-finding-item.is-faulty-turn {
  border-color: #F6AEA9;
  background: #FFFDFD;
  box-shadow: 0 2px 8px rgba(197, 34, 31, 0.06);
}

.conv-finding-item.is-optimal-turn {
  border-color: #CEEAD6;
  background: #FAFDFA;
}

.conv-finding-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.conv-finding-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.turn-badge {
  background: #0B57D0;
  color: #FFFFFF;
  font-size: 0.7rem;
  font-weight: 800;
  padding: 2px 9px;
  border-radius: 999px;
}

.severity-badge {
  font-size: 0.72rem;
  font-weight: 700;
  padding: 2px 10px;
  border-radius: 999px;
}

.severity-badge.sev-critical {
  background: #FCE8E6;
  color: #C5221F;
  border: 1px solid #FAD2CF;
}

.severity-badge.sev-high,
.severity-badge.sev-medium {
  background: #FEF7E0;
  color: #B06000;
  border: 1px solid #FDE293;
}

.severity-badge.sev-optimal {
  background: #E6F4EA;
  color: #137333;
  border: 1px solid #CEEAD6;
}

.btn-apply-single-turn {
  background: #E8F0FE;
  border: 1px solid #A8C7FA;
  color: #0B57D0;
  font-size: 0.72rem;
  font-weight: 700;
  padding: 4px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-apply-single-turn:hover {
  background: #0B57D0;
  color: #FFFFFF;
}

.conv-customer-quote {
  font-size: 0.8rem;
  color: #1F1F1F;
  background: #F1F5F9;
  padding: 6px 10px;
  border-radius: 8px;
  border-left: 3px solid #0B57D0;
}

.conv-diagnosis-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.diag-box {
  padding: 8px 10px;
  border-radius: 10px;
  font-size: 0.76rem;
  line-height: 1.45;
}

.diag-box.issue {
  background: #FFF8F6;
  border: 1px solid #FAD2CF;
  color: #8C1D18;
}

.diag-box.fix {
  background: #F3F8FF;
  border: 1px solid #D3E3FD;
  color: #041E49;
}

.diag-label {
  font-weight: 800;
  margin-bottom: 3px;
}

.conv-turn-diff {
  margin-top: 4px;
}

.fb-sim-mobile-tabs {
  display: none;
}

@media (max-width: 900px) {
  .modal-backdrop {
    padding: 0;
  }

  .fb-sim-modal {
    width: 100vw;
    max-width: 100vw;
    height: 100vh;
    height: 100dvh;
    border-radius: 0;
    border: none;
  }

  .fb-sim-header {
    padding: 10px 14px;
  }

  .fb-sim-subtitle,
  .api-pill {
    display: none;
  }

  .fb-sim-title {
    font-size: 0.92rem;
  }

  .fb-sim-mobile-tabs {
    display: flex;
    gap: 6px;
    padding: 6px 10px;
    background: #F0F4F9;
    border-bottom: 1px solid #DDE3EA;
    flex-shrink: 0;
  }

  .fb-sim-mobile-tab {
    flex: 1;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    padding: 7px 10px;
    border-radius: 10px;
    border: 1px solid transparent;
    background: transparent;
    color: #444746;
    font-size: 0.78rem;
    font-weight: 700;
    cursor: pointer;
  }

  .fb-sim-mobile-tab.active {
    background: #0B57D0;
    color: #FFFFFF;
    box-shadow: 0 2px 6px rgba(11, 87, 208, 0.2);
  }

  .sim-m-badge {
    font-size: 0.7rem;
    font-weight: 800;
    padding: 1px 6px;
    border-radius: 999px;
    background: rgba(0, 0, 0, 0.08);
  }

  .fb-sim-mobile-tab.active .sim-m-badge {
    background: rgba(255, 255, 255, 0.24);
    color: #FFFFFF;
  }

  .fb-sim-body {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-height: 0;
  }

  .messenger-pane,
  .telemetry-pane {
    flex: 1;
    width: 100%;
    min-height: 0;
    border-right: none;
  }

  .fb-sim-body.mobile-sim-chat .telemetry-pane {
    display: none !important;
  }

  .fb-sim-body.mobile-sim-telemetry .messenger-pane {
    display: none !important;
  }

  .messenger-topbar {
    padding: 8px 12px;
    flex-wrap: wrap;
    gap: 8px;
  }

  .messenger-actions {
    width: 100%;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 6px;
  }

  .btn-conv-audit-sim,
  .btn-skill-sim,
  .btn-reset-sim {
    flex: 1;
    justify-content: center;
    text-align: center;
    font-size: 0.7rem;
    padding: 5px 8px;
  }

  .idle-simulation-bar {
    flex-wrap: wrap;
    padding: 6px 10px;
  }

  .messenger-input {
    font-size: 16px !important;
  }
}

@media (max-width: 768px) {
  .conv-diagnosis-grid,
  .candidates-comparison-grid,
  .insight-top-grid {
    grid-template-columns: 1fr;
  }

  .assistant-bubble-col {
    max-width: 90%;
  }
}
</style>
