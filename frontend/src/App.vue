<template>
  <div class="app-shell">
    <!-- Notification Toast -->
    <transition name="toast">
      <div v-if="toastMessage" class="toast-banner" :class="toastType">
        <span>{{ toastMessage }}</span>
        <button class="toast-close" @click="toastMessage = ''">×</button>
      </div>
    </transition>

    <div class="main-layout">
      <!-- Left Sidebar: Upload, Pages/Folders, Documents List, Status Tracker -->
      <DocumentSidebar 
        :documents="documents"
        :pages="pages"
        :active-page-id="activePageId"
        :selected-doc-ids="selectedDocIds"
        :is-uploading="isUploading"
        :is-connected="isConnected"
        @upload-file="handleUpload"
        @toggle-select="handleToggleSelect"
        @toggle-select-all="handleToggleSelectAll"
        @refresh-documents="fetchDocuments"
        @view-detail="inspectingDoc = $event"
        @delete-doc="confirmDeleteDoc"
        @toggle-demo="toggleDemoMode"
        @open-playbook="showPlaybookModal = true"
        @select-page="handleSelectPage"
        @create-page="handleCreatePage"
        @update-page="handleUpdatePage"
        @delete-page="handleDeletePage"
        @assign-page="handleAssignDocPage"
      />

      <!-- Right Main: Chat Q&A with Citations -->
      <ChatArea 
        ref="chatAreaRef"
        :selected-docs="selectedDocuments"
        :all-docs="documents"
        :pages="pages"
        :active-page="activePage"
        :is-connected="isConnected"
        :messages="messages"
        :is-asking="isAsking"
        :active-rules-count="activeRulesCount"
        @ask-question="handleAskQuestion"
        @clear-chat="handleClearChat"
        @open-playbook="showPlaybookModal = true"
        @open-skills="showSkillModal = true"
        @teach-ai="openTeachAi"
      />
    </div>

    <!-- Document Detail Inspector Modal -->
    <DocumentDetailModal 
      v-if="inspectingDoc"
      :doc="inspectingDoc"
      @close="inspectingDoc = null"
      @delete-doc="confirmDeleteDoc"
    />

    <!-- AI Playbook Manager Modal -->
    <PlaybookModal 
      v-if="showPlaybookModal"
      :is-connected="isConnected"
      :pages="pages"
      :active-page-id="activePage ? activePage.id : null"
      @close="showPlaybookModal = false"
      @rules-changed="handleRulesChanged"
    />

    <!-- Agent Skill Registry Modal -->
    <AgentSkillModal
      v-if="showSkillModal"
      :demo-mode="!isConnected"
      :pages="pages"
      :active-page-id="activePage ? activePage.id : null"
      @close="showSkillModal = false"
    />

    <!-- Teach AI / Báo sai & Dạy AI Modal -->
    <TeachAiModal 
      v-if="teachingFeedback"
      :question="teachingFeedback.question"
      :faulty-answer="teachingFeedback.faultyAnswer"
      :document-ids="teachingFeedback.documentIds"
      :message-index="teachingFeedback.messageIndex"
      :is-connected="isConnected"
      :pages="pages"
      :active-page-id="activePage ? activePage.id : null"
      @close="teachingFeedback = null"
      @submitted="handleTeachSubmitted"
    />

    <!-- Confirm Delete Document Modal -->
    <div v-if="deletingDoc" class="modal-backdrop" @click.self="deletingDoc = null">
      <div class="modal-card modal-delete-card">
        <div class="modal-header">
          <div class="modal-title-group text-danger">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="title-icon-danger">
              <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/>
              <line x1="12" y1="9" x2="12" y2="13"/>
              <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
            <h3 class="modal-title">Xác nhận xóa tài liệu</h3>
          </div>
          <button class="btn-close" @click="deletingDoc = null">×</button>
        </div>

        <div class="modal-body">
          <p class="delete-warning-text">
            Bạn có chắc chắn muốn xóa tài liệu <strong>"{{ deletingDoc.filename }}"</strong>?
          </p>

          <div class="danger-box">
            <div class="danger-box-title">Hành động này sẽ xóa vĩnh viễn trong database:</div>
            <ul class="danger-box-list">
              <li>Thông tin tài liệu (document record & stats).</li>
              <li>Toàn bộ các khối nội dung đã bóc tách (canonical blocks).</li>
              <li>Toàn bộ <strong>vector embeddings (pgvector 384-dim)</strong> và chỉ mục tìm kiếm full-text (BM25 tsvector).</li>
            </ul>
          </div>
        </div>

        <div class="modal-footer">
          <button class="btn btn-secondary" :disabled="isDeleting" @click="deletingDoc = null">
            Hủy
          </button>
          <button class="btn btn-danger" :disabled="isDeleting" @click="executeDelete">
            <span v-if="!isDeleting">Xác nhận xóa vĩnh viễn</span>
            <span v-else>Đang xóa...</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { api } from './api'
import { 
  initialMockDocuments, 
  mockChatAnswer, 
  getMockRules,
  getMockPages,
  createMockPage,
  updateMockPage,
  deleteMockPage
} from './mockData'
import DocumentSidebar from './components/DocumentSidebar.vue'
import ChatArea from './components/ChatArea.vue'
import DocumentDetailModal from './components/DocumentDetailModal.vue'
import PlaybookModal from './components/PlaybookModal.vue'
import AgentSkillModal from './components/AgentSkillModal.vue'
import TeachAiModal from './components/TeachAiModal.vue'

const documents = ref([])
const pages = ref([])
const activePageId = ref(null)
const selectedDocIds = ref([])
const messages = ref([])
const isUploading = ref(false)
const isAsking = ref(false)
const isConnected = ref(false)
const inspectingDoc = ref(null)
const deletingDoc = ref(null)
const isDeleting = ref(false)
const toastMessage = ref('')
const toastType = ref('info')
const chatAreaRef = ref(null)

const showPlaybookModal = ref(false)
const showSkillModal = ref(false)
const teachingFeedback = ref(null)
const activeRulesCount = ref(3)

const pollingTimers = new Map()

const activePage = computed(() => {
  if (activePageId.value === '__unassigned__') return null
  if (activePageId.value) {
    return pages.value.find(p => p.id === activePageId.value) || null
  }
  // Nếu đang ở chế độ Tất cả các Page nhưng các tài liệu đang chọn đều thuộc 1 Page duy nhất, tự động nhận diện Page đó
  const selected = documents.value.filter(d => selectedDocIds.value.includes(d.document_id) && d.page_id)
  if (selected.length > 0) {
    const firstPageId = selected[0].page_id
    if (selected.every(d => d.page_id === firstPageId)) {
      return pages.value.find(p => p.id === firstPageId) || null
    }
  }
  return pages.value.length === 1 ? pages.value[0] : null
})

const selectedDocuments = computed(() => {
  return documents.value.filter(d => selectedDocIds.value.includes(d.document_id))
})

const showToast = (msg, type = 'info', timeout = 4000) => {
  toastMessage.value = msg
  toastType.value = type
  setTimeout(() => {
    if (toastMessage.value === msg) {
      toastMessage.value = ''
    }
  }, timeout)
}

/**
 * Kiểm tra backend và tải dữ liệu ban đầu
 */
const initApp = async () => {
  try {
    const online = await api.checkHealth()
    if (online) {
      isConnected.value = true
      await fetchPages()
      await fetchDocuments()
      await fetchInitialRulesCount()
      showToast('Đã kết nối thành công tới DocMind Backend (cổng 8090)', 'success')
    } else {
      useDemoData()
      showToast('Backend chưa chạy. Đang hiển thị giao diện ở Chế độ Demo trực quan.', 'info')
    }
  } catch {
    useDemoData()
  }
}

const useDemoData = () => {
  isConnected.value = false
  pages.value = getMockPages()
  documents.value = JSON.parse(JSON.stringify(initialMockDocuments))
  if (!activePageId.value && pages.value.length > 0) {
    activePageId.value = pages.value[0].id
  }
  syncSelectedDocsForActivePage()
  fetchInitialRulesCount()
}

const syncSelectedDocsForActivePage = () => {
  const scopedDocs = documents.value.filter(d => {
    if (d.status !== 'READY') return false
    if (!activePageId.value) return true
    if (activePageId.value === '__unassigned__') return !d.page_id
    return d.page_id === activePageId.value
  })
  selectedDocIds.value = scopedDocs.map(d => d.document_id)
}

const handleSelectPage = (pageId) => {
  activePageId.value = pageId
  try {
    if (pageId) {
      localStorage.setItem('docmind_active_page_id', pageId)
    } else {
      localStorage.removeItem('docmind_active_page_id')
    }
  } catch {
    // ignore storage errors
  }
  syncSelectedDocsForActivePage()
  fetchInitialRulesCount()
}

const fetchPages = async () => {
  if (!isConnected.value) {
    pages.value = getMockPages()
    return
  }
  try {
    pages.value = await api.getPages()
    if (!activePageId.value && pages.value.length > 0) {
      let savedPageId = null
      try {
        savedPageId = localStorage.getItem('docmind_active_page_id')
      } catch {
        // ignore
      }
      if (savedPageId && pages.value.some(p => p.id === savedPageId)) {
        activePageId.value = savedPageId
      } else {
        activePageId.value = pages.value[0].id
      }
    }
  } catch (err) {
    console.warn('Fetch pages error:', err)
    pages.value = getMockPages()
  }
}

const handleCreatePage = async (payload) => {
  try {
    let created = null
    if (isConnected.value) {
      created = await api.createPage(payload)
    } else {
      created = createMockPage(payload)
    }
    pages.value.push(created)
    handleSelectPage(created.id)
    showToast(`📂 Đã tạo Page "${created.name}" thành công!`, 'success')
  } catch (err) {
    showToast(`Lỗi tạo Page: ${err.message}`, 'error')
  }
}

const handleUpdatePage = async ({ id, data }) => {
  try {
    let updated = null
    if (isConnected.value) {
      updated = await api.updatePage(id, data)
    } else {
      updated = updateMockPage(id, data)
    }
    const idx = pages.value.findIndex(p => p.id === id)
    if (idx !== -1 && updated) {
      pages.value[idx] = { ...pages.value[idx], ...updated }
    }
    showToast(`✏️ Đã cập nhật thông tin Page "${data.name}"`, 'success')
  } catch (err) {
    showToast(`Lỗi cập nhật Page: ${err.message}`, 'error')
  }
}

const handleDeletePage = async (page) => {
  try {
    if (isConnected.value) {
      await api.deletePage(page.id)
    } else {
      deleteMockPage(page.id)
    }
    pages.value = pages.value.filter(p => p.id !== page.id)
    // Chuyển các tài liệu thuộc page này về chưa phân loại
    documents.value.forEach(d => {
      if (d.page_id === page.id) d.page_id = null
    })
    if (activePageId.value === page.id) {
      handleSelectPage(null)
    }
    showToast(`Đã xóa Page "${page.name}". Tài liệu bên trong được chuyển về Chưa phân loại.`, 'info')
  } catch (err) {
    showToast(`Lỗi xóa Page: ${err.message}`, 'error')
  }
}

const handleAssignDocPage = async ({ documentId, docId, pageId }) => {
  const targetDocId = documentId || docId
  try {
    if (isConnected.value) {
      await api.assignDocumentPage(targetDocId, pageId)
    }
    const doc = documents.value.find(d => d.document_id === targetDocId)
    if (doc) {
      doc.page_id = pageId || null
    }
    syncSelectedDocsForActivePage()
    const targetPage = pages.value.find(p => p.id === pageId)
    showToast(
      targetPage
        ? `📂 Đã chuyển tài liệu vào Page "${targetPage.name}"`
        : 'Đã gỡ tài liệu khỏi Page (Chưa phân loại)',
      'success',
      2500
    )
  } catch (err) {
    showToast(`Lỗi gán Page cho tài liệu: ${err.message}`, 'error')
  }
}

const toggleDemoMode = async () => {
  if (!isConnected.value) {
    // Thử kết nối lại API thật
    try {
      showToast('Đang thử kết nối tới backend localhost:8090...', 'info')
      const online = await api.checkHealth()
      if (online) {
        isConnected.value = true
        await fetchPages()
        await fetchDocuments()
        await fetchInitialRulesCount()
        showToast('Kết nối backend thành công!', 'success')
      } else {
        showToast('Không thể kết nối localhost:8090. Vẫn giữ Chế độ Demo.', 'error')
      }
    } catch (e) {
      showToast(`Kết nối thất bại: ${e.message}`, 'error')
    }
  } else {
    // Chuyển sang Demo
    useDemoData()
    showToast('Đã chuyển sang Chế độ Demo (dữ liệu mẫu)', 'info')
  }
}

const fetchInitialRulesCount = async () => {
  const effectivePageId = activePage.value ? activePage.value.id : undefined
  try {
    if (isConnected.value) {
      const list = await api.getRules({ active: true, pageId: effectivePageId })
      activeRulesCount.value = list.length
    } else {
      activeRulesCount.value = getMockRules({ active: true, pageId: effectivePageId }).length
    }
  } catch {
    activeRulesCount.value = getMockRules({ active: true, pageId: effectivePageId }).length
  }
}

const openTeachAi = (event) => {
  teachingFeedback.value = event
}

const handleTeachSubmitted = ({ messageIndex, learnedRule, regeneratedAnswer, appliedRules, citations }) => {
  if (messageIndex >= 0 && messageIndex < messages.value.length) {
    messages.value[messageIndex].content = regeneratedAnswer
    messages.value[messageIndex].newly_learned_rule = learnedRule
    messages.value[messageIndex].applied_rules = appliedRules || [learnedRule]
    if (citations && citations.length > 0) {
      messages.value[messageIndex].citations = citations
    }
  }
  activeRulesCount.value++
  showToast('✨ AI đã tiếp thu quy tắc mới vào Playbook và tái tạo câu trả lời!', 'success')
  chatAreaRef.value?.scrollToBottom()
}

const handleRulesChanged = ({ rules }) => {
  if (rules) {
    activeRulesCount.value = rules.filter(r => r.active).length
  }
}

/**
 * Lấy danh sách tài liệu từ backend
 */
const fetchDocuments = async () => {
  if (!isConnected.value) {
    showToast('Đã làm mới dữ liệu demo', 'info', 2000)
    return
  }

  try {
    const list = await api.getDocuments()
    documents.value = list
    syncSelectedDocsForActivePage()

    // Tự động poll các tài liệu đang ở trạng thái PARSING
    list.filter(d => d.status === 'PARSING').forEach(d => {
      startPolling(d.document_id)
    })
  } catch (err) {
    console.error('Fetch docs error:', err)
    showToast(`Lỗi lấy danh sách tài liệu: ${err.message}`, 'error')
  }
}

/**
 * Bắt đầu poll trạng thái tài liệu
 */
const startPolling = (docId) => {
  if (pollingTimers.has(docId)) return

  const timer = setInterval(async () => {
    if (!isConnected.value) {
      clearInterval(timer)
      pollingTimers.delete(docId)
      return
    }

    try {
      const statusRes = await api.getDocumentStatus(docId)
      const idx = documents.value.findIndex(d => d.document_id === docId)
      if (idx !== -1) {
        documents.value[idx] = { ...documents.value[idx], ...statusRes }

        // Nếu đã hoàn thành (READY hoặc FAILED), dừng poll
        if (statusRes.status === 'READY' || statusRes.status === 'FAILED') {
          clearInterval(timer)
          pollingTimers.delete(docId)

          if (statusRes.status === 'READY') {
            showToast(`Tài liệu "${statusRes.filename}" đã phân tích xong!`, 'success')
            if (!selectedDocIds.value.includes(docId)) {
              selectedDocIds.value.push(docId)
            }
          } else {
            showToast(`Tài liệu "${statusRes.filename}" phân tích thất bại!`, 'error')
          }
        }
      }
    } catch (e) {
      console.warn('Poll error for doc', docId, e)
    }
  }, 2000)

  pollingTimers.set(docId, timer)
}

/**
 * Xử lý Upload file (gắn tự động vào Page đang chọn nếu có)
 */
const handleUpload = async (file) => {
  isUploading.value = true
  const uploadPageId = activePage.value ? activePage.value.id : null

  if (!isConnected.value) {
    // Giả lập upload trên Demo Mode
    setTimeout(() => {
      const newDocId = `doc-demo-${Date.now()}`
      const newDoc = {
        document_id: newDocId,
        filename: file.name,
        status: 'PARSING',
        pages: Math.floor(Math.random() * 15) + 2,
        page_id: uploadPageId,
        engine_breakdown: {},
        created_at: new Date().toISOString()
      }
      documents.value.unshift(newDoc)
      isUploading.value = false
      showToast(
        uploadPageId
          ? `Đã tải lên "${file.name}" vào Page "${activePage.value.name}". Đang bóc tách...`
          : `Đã tải lên "${file.name}". Đang bóc tách block...`,
        'info'
      )

      // Giả lập chuyển đổi trạng thái sang READY sau 4 giây
      setTimeout(() => {
        const target = documents.value.find(d => d.document_id === newDocId)
        if (target) {
          target.status = 'READY'
          target.engine_breakdown = { pymupdf: target.pages }
          selectedDocIds.value.push(newDocId)
          showToast(`Tài liệu "${file.name}" đã phân tích sẵn sàng!`, 'success')
        }
      }, 4000)
    }, 1200)
    return
  }

  try {
    const res = await api.uploadDocument(file, uploadPageId)
    showToast(`Đã tải lên "${file.name}". Job ID: ${res.job_id || 'đang xử lý'}`, 'success')

    // Thêm tạm vào danh sách và bắt đầu poll
    const existingIndex = documents.value.findIndex(d => d.document_id === res.document_id)
    if (existingIndex !== -1) {
      documents.value[existingIndex].status = res.status || 'PARSING'
      documents.value[existingIndex].page_id = uploadPageId
    } else {
      documents.value.unshift({
        document_id: res.document_id,
        filename: file.name,
        status: res.status || 'PARSING',
        pages: 0,
        page_id: uploadPageId,
        engine_breakdown: {},
        created_at: new Date().toISOString()
      })
    }

    startPolling(res.document_id)
  } catch (err) {
    showToast(`Upload thất bại: ${err.message}`, 'error')
  } finally {
    isUploading.value = false
  }
}

/**
 * Chọn / bỏ chọn tài liệu
 */
const handleToggleSelect = (docId) => {
  const doc = documents.value.find(d => d.document_id === docId)
  if (doc && doc.status !== 'READY') return // Chỉ cho phép chọn tài liệu đã READY

  const idx = selectedDocIds.value.indexOf(docId)
  if (idx !== -1) {
    selectedDocIds.value.splice(idx, 1)
  } else {
    selectedDocIds.value.push(docId)
  }
}

const handleToggleSelectAll = () => {
  const readyIds = documents.value
    .filter(d => {
      if (d.status !== 'READY') return false
      if (!activePageId.value) return true
      if (activePageId.value === '__unassigned__') return !d.page_id
      return d.page_id === activePageId.value
    })
    .map(d => d.document_id)
  const isAll = readyIds.every(id => selectedDocIds.value.includes(id))

  if (isAll) {
    selectedDocIds.value = []
  } else {
    selectedDocIds.value = [...readyIds]
  }
}

/**
 * Hỏi đáp chat RAG (tự động cô lập theo Page đang chọn)
 */
const handleAskQuestion = async ({ question, documentIds, topK }) => {
  // Tìm câu hỏi và câu trả lời liền trước để hỗ trợ conversational correction
  const prevUserMsg = [...messages.value].reverse().find(m => m.role === 'user')
  const prevAssistantMsg = [...messages.value].reverse().find(m => m.role === 'assistant')
  const currentPageId = activePage.value ? activePage.value.id : null

  // Thêm tin nhắn câu hỏi của user
  messages.value.push({
    role: 'user',
    content: question
  })

  isAsking.value = true
  chatAreaRef.value?.scrollToBottom()

  if (!isConnected.value) {
    // Trả lời trên demo mode với mockChatAnswer
    setTimeout(() => {
      const res = mockChatAnswer({
        question,
        previousQuestion: prevUserMsg?.content,
        previousAnswer: prevAssistantMsg?.content,
        pageId: currentPageId
      })

      messages.value.push({
        role: 'assistant',
        question,
        content: res.answer,
        citations: res.citations || [],
        applied_rules: res.applied_rules || [],
        newly_learned_rule: res.newly_learned_rule || null
      })

      if (res.newly_learned_rule) {
        activeRulesCount.value++
        showToast('✨ AI đã tự học quy tắc mới từ phản hồi hội thoại của bạn!', 'success')
      }

      isAsking.value = false
      chatAreaRef.value?.scrollToBottom()
    }, 1200)
    return
  }

  try {
    const res = await api.chat({
      documentIds,
      question,
      topK,
      previousQuestion: prevUserMsg?.content,
      previousAnswer: prevAssistantMsg?.content,
      pageId: currentPageId
    })

    messages.value.push({
      role: 'assistant',
      question,
      content: res.answer || 'Không tìm thấy câu trả lời phù hợp trong tài liệu.',
      citations: res.citations || [],
      applied_rules: res.applied_rules || [],
      newly_learned_rule: res.newly_learned_rule || null
    })

    if (res.newly_learned_rule) {
      activeRulesCount.value++
      showToast('✨ AI đã tự học quy tắc mới từ phản hồi hội thoại của bạn!', 'success')
    }
  } catch (err) {
    messages.value.push({
      role: 'assistant',
      question,
      content: `⚠️ **Lỗi truy vấn RAG:** ${err.message}\n\nHãy đảm bảo tài liệu đã ở trạng thái READY và service backend đang hoạt động.`,
      citations: [],
      applied_rules: [],
      newly_learned_rule: null
    })
  } finally {
    isAsking.value = false
    chatAreaRef.value?.scrollToBottom()
  }
}

const handleClearChat = () => {
  messages.value = []
}

/**
 * Mở modal xác nhận xóa
 */
const confirmDeleteDoc = (doc) => {
  inspectingDoc.value = null
  deletingDoc.value = doc
}

/**
 * Thực thi xóa tài liệu và embedding trong database
 */
const executeDelete = async () => {
  if (!deletingDoc.value) return
  const doc = deletingDoc.value
  isDeleting.value = true

  try {
    if (isConnected.value) {
      await api.deleteDocument(doc.document_id)
    }

    // Dừng polling nếu đang poll file này
    if (pollingTimers.has(doc.document_id)) {
      clearInterval(pollingTimers.get(doc.document_id))
      pollingTimers.delete(doc.document_id)
    }

    // Xóa khỏi danh sách documents
    documents.value = documents.value.filter(d => d.document_id !== doc.document_id)

    // Xóa khỏi danh sách đã chọn
    selectedDocIds.value = selectedDocIds.value.filter(id => id !== doc.document_id)

    showToast(`Đã xóa vĩnh viễn "${doc.filename}" và toàn bộ dữ liệu embedding trong DB!`, 'success')
    deletingDoc.value = null
  } catch (err) {
    showToast(`Lỗi khi xóa tài liệu: ${err.message}`, 'error')
  } finally {
    isDeleting.value = false
  }
}

onMounted(() => {
  initApp()
})

onUnmounted(() => {
  pollingTimers.forEach(timer => clearInterval(timer))
  pollingTimers.clear()
})
</script>

<style scoped>
.app-shell {
  width: 100vw;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-primary);
}

/* Modal styles in App.vue for delete confirmation */
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.75);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.modal-delete-card {
  width: 100%;
  max-width: 480px;
  background: var(--bg-card);
  border: 1px solid rgba(244, 63, 94, 0.3);
  border-radius: var(--radius-lg);
  box-shadow: 0 20px 45px rgba(0, 0, 0, 0.7), 0 0 20px rgba(244, 63, 94, 0.15);
  overflow: hidden;
  animation: modalEnter 0.2s ease-out;
}

@keyframes modalEnter {
  from {
    opacity: 0;
    transform: scale(0.95) translateY(10px);
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

.title-icon-danger {
  color: #fb7185;
}

.modal-title {
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--text-main);
}

.text-danger .modal-title {
  color: #fb7185;
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
  gap: 14px;
}

.delete-warning-text {
  font-size: 0.95rem;
  color: var(--text-main);
  line-height: 1.5;
}

.delete-warning-text strong {
  color: #ffffff;
}

.danger-box {
  background: rgba(244, 63, 94, 0.08);
  border: 1px solid rgba(244, 63, 94, 0.25);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
}

.danger-box-title {
  font-size: 0.78rem;
  font-weight: 600;
  color: #fb7185;
  margin-bottom: 6px;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.danger-box-list {
  padding-left: 18px;
  font-size: 0.8rem;
  color: #fecdd3;
  line-height: 1.55;
}

.danger-box-list strong {
  color: #ffffff;
}

.modal-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--border-subtle);
  background: rgba(10, 15, 26, 0.5);
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.main-layout {
  flex: 1;
  display: flex;
  height: 100%;
  overflow: hidden;
}

/* Toast Banner */
.toast-banner {
  position: fixed;
  top: 16px;
  right: 20px;
  z-index: 999;
  padding: 10px 16px;
  border-radius: var(--radius-md);
  font-size: 0.82rem;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 12px;
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(12px);
}

.toast-banner.success {
  background: rgba(16, 185, 129, 0.9);
  color: #ffffff;
  border: 1px solid rgba(16, 185, 129, 0.4);
}

.toast-banner.info {
  background: rgba(99, 102, 241, 0.9);
  color: #ffffff;
  border: 1px solid rgba(99, 102, 241, 0.4);
}

.toast-banner.error {
  background: rgba(244, 63, 94, 0.9);
  color: #ffffff;
  border: 1px solid rgba(244, 63, 94, 0.4);
}

.toast-close {
  background: none;
  border: none;
  color: inherit;
  font-size: 1.1rem;
  cursor: pointer;
  padding: 0 2px;
  line-height: 1;
}

.toast-enter-active,
.toast-leave-active {
  transition: all 0.25s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-12px);
}
</style>
