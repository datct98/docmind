/**
 * DocMind API Service Layer
 * Tương tác trực tiếp với backend Spring Boot (cổng 8090) qua /api
 */

const API_BASE = '/api'

export const api = {
  /**
   * Kiểm tra trạng thái Mock Mode (luôn tắt - gọi thẳng vào API)
   */
  isMockMode() {
    return false
  },
  setMockMode() {
    // No-op: luôn gọi thẳng vào API
  },

  /**
   * Kiểm tra kết nối backend
   */
  async checkHealth() {
    try {
      const res = await fetch(`${API_BASE}/documents`, {
        method: 'GET',
        headers: { Accept: 'application/json' }
      })
      return res.ok
    } catch {
      return false
    }
  },

  /**
   * Lấy trạng thái cấu hình LLM & API Key (và tuỳ chọn ping live)
   * GET /api/llm/health?ping=true|false
   */
  async getLlmHealth(ping = false) {
    const res = await fetch(`${API_BASE}/llm/health?ping=${ping ? 'true' : 'false'}`, {
      method: 'GET',
      headers: { Accept: 'application/json' }
    })
    if (!res.ok) {
      throw new Error(`Không thể kiểm tra trạng thái LLM (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Ping trực tiếp LLM Endpoint & API Key để đo độ trễ và xác thực Key
   * POST /api/llm/ping
   */
  async pingLlm() {
    const res = await fetch(`${API_BASE}/llm/ping`, {
      method: 'POST',
      headers: { Accept: 'application/json' }
    })
    if (!res.ok) {
      throw new Error(`Lỗi ping LLM Endpoint (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Lấy danh sách tất cả các Page / Thư mục tri thức
   * GET /api/pages
   */
  async getPages() {
    const res = await fetch(`${API_BASE}/pages`, {
      method: 'GET',
      headers: { Accept: 'application/json' }
    })
    if (!res.ok) {
      throw new Error(`Không thể lấy danh sách Page tri thức (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Lấy danh sách 6 bộ Industry Persona Preset mẫu (Configurable Skin)
   * GET /api/pages/industry-templates
   */
  async getIndustryTemplates() {
    const res = await fetch(`${API_BASE}/pages/industry-templates`, {
      method: 'GET',
      headers: { Accept: 'application/json' }
    })
    if (!res.ok) {
      throw new Error(`Không thể lấy danh sách mẫu ngành hàng (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Tạo Page / Thư mục tri thức mới
   * POST /api/pages
   */
  async createPage(pageData) {
    const res = await fetch(`${API_BASE}/pages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(pageData)
    })
    if (!res.ok) {
      let errorMsg = `Lỗi tạo Page mới (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }
    return await res.json()
  },

  /**
   * Cập nhật thông tin Page / Thư mục tri thức
   * PUT /api/pages/:id
   */
  async updatePage(id, pageData) {
    const res = await fetch(`${API_BASE}/pages/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(pageData)
    })
    if (!res.ok) {
      let errorMsg = `Lỗi cập nhật Page (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }
    return await res.json()
  },

  /**
   * Xóa Page / Thư mục tri thức
   * DELETE /api/pages/:id
   */
  async deletePage(id) {
    const res = await fetch(`${API_BASE}/pages/${encodeURIComponent(id)}`, {
      method: 'DELETE'
    })
    if (!res.ok && res.status !== 204) {
      throw new Error(`Lỗi xóa Page (HTTP ${res.status})`)
    }
    return true
  },

  /**
   * Lấy danh sách tất cả tài liệu (có thể lọc theo page_id)
   * GET /api/documents
   */
  async getDocuments(pageId = null) {
    const url = pageId ? `${API_BASE}/documents?page_id=${encodeURIComponent(pageId)}` : `${API_BASE}/documents`
    const res = await fetch(url)
    if (!res.ok) {
      throw new Error(`Không thể lấy danh sách tài liệu (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Lấy trạng thái của một tài liệu
   * GET /api/documents/:id
   */
  async getDocumentStatus(id) {
    const res = await fetch(`${API_BASE}/documents/${encodeURIComponent(id)}`)
    if (!res.ok) {
      throw new Error(`Không thể lấy trạng thái tài liệu ${id} (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Upload tài liệu mới (gán trực tiếp vào Page nếu có pageId, kèm tùy chọn hiệu lực thời gian)
   * POST /api/documents
   * multipart/form-data: file, page_id, effective_from, effective_to, is_active
   */
  async uploadDocument(file, pageId = null, lifecycle = null) {
    const formData = new FormData()
    formData.append('file', file)
    if (pageId) {
      formData.append('page_id', pageId)
    }
    if (lifecycle && typeof lifecycle === 'object') {
      if (lifecycle.effective_from) formData.append('effective_from', lifecycle.effective_from)
      if (lifecycle.effective_to) formData.append('effective_to', lifecycle.effective_to)
      if (lifecycle.is_active !== undefined && lifecycle.is_active !== null) {
        formData.append('is_active', String(lifecycle.is_active))
      }
    }

    const res = await fetch(`${API_BASE}/documents`, {
      method: 'POST',
      body: formData
    })

    if (!res.ok) {
      let errorMsg = `Upload thất bại (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return await res.json()
  },

  /**
   * Cập nhật thời hạn hiệu lực (effective_from, effective_to) và trạng thái hoạt động (is_active) của tài liệu
   * PUT /api/documents/:id/lifecycle
   */
  async updateDocumentLifecycle(docId, { effective_from = null, effective_to = null, is_active = true }) {
    const res = await fetch(`${API_BASE}/documents/${encodeURIComponent(docId)}/lifecycle`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({
        effective_from: effective_from || '',
        effective_to: effective_to || '',
        is_active: is_active !== false
      })
    })
    if (!res.ok) {
      let errorMsg = `Không thể cập nhật hiệu lực tài liệu (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }
    return await res.json()
  },

  /**
   * Gán / chuyển tài liệu sang một Page khác
   * PUT /api/documents/:id/page
   */
  async assignDocumentPage(docId, pageId) {
    const res = await fetch(`${API_BASE}/documents/${encodeURIComponent(docId)}/page`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({ page_id: pageId || null })
    })
    if (!res.ok) {
      throw new Error(`Không thể chuyển thư mục cho tài liệu (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Hỏi đáp chat với tài liệu (RAG Grounded Chat)
   * POST /api/chat
   * Body: { document_ids: string[], question: string, top_k?: number, previous_question?: string, previous_answer?: string, page_id?: string }
   */
  async chat({ documentIds, question, topK = 5, previousQuestion = null, previousAnswer = null, pageId = null }) {
    const payload = {
      document_ids: documentIds,
      question,
      top_k: topK
    }
    if (previousQuestion) payload.previous_question = previousQuestion
    if (previousAnswer) payload.previous_answer = previousAnswer
    if (pageId) payload.page_id = pageId

    const res = await fetch(`${API_BASE}/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(payload)
    })

    if (!res.ok) {
      let errorMsg = `Lỗi truy vấn chat (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return await res.json()
  },

  /**
   * Báo sai & Dạy AI (Explicit Feedback Loop)
   * POST /api/feedback
   */
  async submitFeedback({ documentIds, question, faultyAnswer, userCorrection, pageId = null, source = null, history = null, appliedRuleIds = null }) {
    const payload = {
      document_ids: documentIds,
      question,
      faulty_answer: faultyAnswer,
      user_correction: userCorrection
    }
    if (pageId) payload.page_id = pageId
    if (source) payload.source = source
    if (Array.isArray(history) && history.length > 0) payload.history = history
    if (Array.isArray(appliedRuleIds) && appliedRuleIds.length > 0) payload.applied_rule_ids = appliedRuleIds

    const res = await fetch(`${API_BASE}/feedback`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(payload)
    })

    if (!res.ok) {
      let errorMsg = `Lỗi gửi phản hồi dạy AI (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return await res.json()
  },

  /**
   * Lấy danh sách quy tắc kinh nghiệm trong Playbook
   * GET /api/rules?category=&active=&search=&page_id=
   */
  async getRules(params = {}) {
    const searchParams = new URLSearchParams()
    if (params.category) searchParams.set('category', params.category)
    if (params.active !== undefined && params.active !== null && params.active !== '') {
      searchParams.set('active', params.active)
    }
    if (params.search) searchParams.set('search', params.search)
    if (params.pageId) searchParams.set('page_id', params.pageId)
    if (params.includeGlobal !== undefined) searchParams.set('include_global', params.includeGlobal)
    if (params.dimension) searchParams.set('dimension', params.dimension)
    if (params.reviewStatus) searchParams.set('review_status', params.reviewStatus)

    const queryStr = searchParams.toString()
    const url = queryStr ? `${API_BASE}/rules?${queryStr}` : `${API_BASE}/rules`

    const res = await fetch(url, {
      method: 'GET',
      headers: { Accept: 'application/json' }
    })

    if (!res.ok) {
      throw new Error(`Không thể tải danh sách quy tắc Playbook (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Tạo quy tắc kinh nghiệm mới thủ công
   * POST /api/rules
   */
  async createRule(ruleData) {
    const res = await fetch(`${API_BASE}/rules`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(ruleData)
    })

    if (!res.ok) {
      let errorMsg = `Lỗi tạo quy tắc mới (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
        else if (errJson.detail) errorMsg = errJson.detail
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return await res.json()
  },

  /**
   * Cập nhật quy tắc hoặc bật/tắt kích hoạt
   * PUT /api/rules/:id
   */
  async updateRule(id, ruleData) {
    const res = await fetch(`${API_BASE}/rules/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(ruleData)
    })

    if (!res.ok) {
      let errorMsg = `Lỗi cập nhật quy tắc (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
        else if (errJson.detail) errorMsg = errJson.detail
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return await res.json()
  },

  /**
   * Khôi phục quy tắc về phiên bản trước (1-Click Rollback)
   * POST /api/rules/:id/rollback
   */
  async rollbackRule(id) {
    const res = await fetch(`${API_BASE}/rules/${encodeURIComponent(id)}/rollback`, {
      method: 'POST',
      headers: {
        Accept: 'application/json'
      }
    })

    if (!res.ok) {
      let errorMsg = `Lỗi khôi phục phiên bản quy tắc (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
        else if (errJson.detail) errorMsg = errJson.detail
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return await res.json()
  },

  /**
   * Duyệt quy tắc từ Hàng đợi Chờ duyệt (Staging Queue -> APPROVED + active)
   * POST /api/rules/:id/approve
   */
  async approveRule(id) {
    const res = await fetch(`${API_BASE}/rules/${encodeURIComponent(id)}/approve`, {
      method: 'POST',
      headers: { Accept: 'application/json' }
    })
    if (!res.ok) {
      throw new Error(`Không thể duyệt quy tắc (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Từ chối quy tắc trong Hàng đợi Chờ duyệt (Staging Queue -> REJECTED)
   * POST /api/rules/:id/reject
   */
  async rejectRule(id) {
    const res = await fetch(`${API_BASE}/rules/${encodeURIComponent(id)}/reject`, {
      method: 'POST',
      headers: { Accept: 'application/json' }
    })
    if (!res.ok) {
      throw new Error(`Không thể từ chối quy tắc (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Kích hoạt Luồng 2 (Cold Path): Trích xuất mẫu câu từ log hội thoại chốt đơn & Kiểm định Bảo mật AI
   * POST /api/rules/nightly-extract
   */
  async extractNightlyRules(payload) {
    const res = await fetch(`${API_BASE}/rules/nightly-extract`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(payload)
    })
    if (!res.ok) {
      throw new Error(`Lỗi trích xuất & kiểm định bảo mật Cold Path (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Xóa quy tắc khỏi hệ thống
   * DELETE /api/rules/:id
   */
  async deleteRule(id) {
    const res = await fetch(`${API_BASE}/rules/${encodeURIComponent(id)}`, {
      method: 'DELETE'
    })

    if (!res.ok && res.status !== 204) {
      let errorMsg = `Lỗi xóa quy tắc (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return true
  },

  /**
   * Nạp nhanh Playbook Rules (PB-*) & Sample Dialogues (FD-*) bằng nội dung văn bản (.yaml, .md, .json)
   * Kích hoạt ACTIVE ngay lập tức (bỏ bước duyệt thủ công) sau khi qua cổng kiểm định an toàn.
   * POST /api/rules/upload-playbook
   */
  async uploadPlaybookRaw({ content, format = '', pageId = null, replaceExisting = true }) {
    const payload = {
      content,
      format: format || undefined,
      page_id: pageId || null,
      replace_existing: replaceExisting
    }
    const res = await fetch(`${API_BASE}/rules/upload-playbook`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(payload)
    })
    if (!res.ok) {
      let errorMsg = `Lỗi nạp Playbook (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
        else if (errJson.detail) errorMsg = errJson.detail
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }
    return await res.json()
  },

  /**
   * Nạp file Playbook (.yaml, .yml, .md, .markdown, .json) trực tiếp theo từng Page
   * Kích hoạt ACTIVE ngay lập tức (bỏ bước duyệt thủ công) sau khi qua cổng kiểm định an toàn.
   * POST /api/rules/upload-playbook-file
   */
  async uploadPlaybookFile(file, pageId = null, replaceExisting = true) {
    const formData = new FormData()
    formData.append('file', file)
    if (pageId) {
      formData.append('page_id', pageId)
    }
    formData.append('replace_existing', String(replaceExisting))
    const res = await fetch(`${API_BASE}/rules/upload-playbook-file`, {
      method: 'POST',
      body: formData
    })
    if (!res.ok) {
      let errorMsg = `Lỗi tải file Playbook (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
        else if (errJson.detail) errorMsg = errJson.detail
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }
    return await res.json()
  },

  /**
   * Lấy danh sách Hội thoại mẫu (Sample Dialogues FD-*) theo Page
   * GET /api/rules/sample-dialogues?page_id=&active=&include_global=&review_status=
   */
  async getSampleDialogues(params = {}) {
    const searchParams = new URLSearchParams()
    if (params.pageId) searchParams.set('page_id', params.pageId)
    if (params.active !== undefined && params.active !== null && params.active !== '') {
      searchParams.set('active', params.active)
    }
    if (params.includeGlobal !== undefined) {
      searchParams.set('include_global', params.includeGlobal)
    }
    if (params.reviewStatus) {
      searchParams.set('review_status', params.reviewStatus)
    }
    const qs = searchParams.toString()
    const url = qs ? `${API_BASE}/rules/sample-dialogues?${qs}` : `${API_BASE}/rules/sample-dialogues`
    const res = await fetch(url, {
      method: 'GET',
      headers: { Accept: 'application/json' }
    })
    if (!res.ok) {
      throw new Error(`Không thể tải danh sách Hội thoại mẫu (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Xóa tài liệu và toàn bộ blocks, vector embeddings liên quan
   * DELETE /api/documents/:id
   */
  async deleteDocument(id) {
    const res = await fetch(`${API_BASE}/documents/${encodeURIComponent(id)}`, {
      method: 'DELETE'
    })

    if (!res.ok && res.status !== 204) {
      let errorMsg = `Xóa tài liệu thất bại (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return true
  },

  /**
   * Gửi tin nhắn khách hàng tới API Bot Trực Fanpage Facebook riêng biệt
   * POST /api/bot/fanpage/message
   */
  async sendFanpageBotMessage({
    pageId = null,
    senderId = 'fb-user-sim',
    customerName = null,
    message = '',
    messages = null,
    history = [],
    documentIds = [],
    topK = 5,
    idleSeconds = null,
    replyDelaySeconds = null,
    previousTemperature = null,
    action = null,
    isIdleReengage = false
  }) {
    const payload = {
      page_id: pageId,
      sender_id: senderId,
      customer_name: customerName,
      message,
      history,
      document_ids: documentIds,
      top_k: topK
    }
    if (messages && messages.length > 0) {
      payload.messages = messages
    }
    if (idleSeconds !== null && idleSeconds !== undefined) {
      payload.idle_seconds = Number(idleSeconds)
    }
    if (replyDelaySeconds !== null && replyDelaySeconds !== undefined) {
      payload.reply_delay_seconds = Number(replyDelaySeconds)
    }
    if (previousTemperature !== null && previousTemperature !== undefined) {
      payload.previous_temperature = Number(previousTemperature)
    }
    if (action) {
      payload.action = action
    } else if (isIdleReengage) {
      payload.action = 'REENGAGE'
    }

    let res
    for (let attempt = 1; attempt <= 2; attempt++) {
      try {
        res = await fetch(`${API_BASE}/bot/fanpage/message`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json'
          },
          body: JSON.stringify(payload)
        })
        if (attempt === 1 && (res.status === 502 || res.status === 503 || res.status === 504)) {
          await new Promise(r => setTimeout(r, 1200))
          continue
        }
        break
      } catch (fetchErr) {
        if (attempt === 1) {
          await new Promise(r => setTimeout(r, 1200))
          continue
        }
        throw fetchErr
      }
    }

    if (!res.ok) {
      let errorMsg = `Lỗi gọi API Bot Fanpage (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return await res.json()
  },

  /**
   * Kích hoạt tin nhắn chủ động kéo lại tương tác khi khách im lặng / hạ nhiệt (Proactive Re-engagement)
   * POST /api/bot/fanpage/reengage (hoặc /api/bot/fanpage/message với action='REENGAGE')
   */
  async reengageFanpageBot({ pageId = null, senderId = 'fb-user-sim', idleSeconds = 300, previousTemperature = null, history = [], documentIds = [] }) {
    return this.sendFanpageBotMessage({
      pageId,
      senderId,
      idleSeconds,
      previousTemperature,
      history,
      documentIds,
      action: 'REENGAGE',
      isIdleReengage: true
    })
  },

  /**
   * Lấy danh sách Kỹ năng Agent (Agent Skill Registry: PSYCHOLOGY, SALES_STRATEGY, COMMUNICATION)
   * GET /api/skills?category=&active=&search=&page_id=&include_global=
   */
  async getSkills(params = {}) {
    const searchParams = new URLSearchParams()
    if (params.category) searchParams.set('category', params.category)
    if (params.active !== undefined && params.active !== null && params.active !== '') {
      searchParams.set('active', params.active)
    }
    if (params.search) searchParams.set('search', params.search)
    if (params.pageId) searchParams.set('page_id', params.pageId)
    if (params.includeGlobal !== undefined) searchParams.set('include_global', params.includeGlobal)

    const queryStr = searchParams.toString()
    const url = queryStr ? `${API_BASE}/skills?${queryStr}` : `${API_BASE}/skills`

    const res = await fetch(url, {
      method: 'GET',
      headers: { Accept: 'application/json' }
    })

    if (!res.ok) {
      throw new Error(`Không thể tải danh sách Kỹ năng Agent (HTTP ${res.status})`)
    }
    return await res.json()
  },

  /**
   * Phân tích mô tả kỹ năng bằng ngôn ngữ tự nhiên (1-Step Smart Skill)
   * POST /api/skills/smart-parse
   */
  async smartParseSkill(skillData) {
    const res = await fetch(`${API_BASE}/skills/smart-parse`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(skillData)
    })

    if (!res.ok) {
      let errorMsg = `Lỗi phân tích Kỹ năng tự động (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
        else if (errJson.detail) errorMsg = errJson.detail
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return await res.json()
  },

  /**
   * Tạo Kỹ năng Agent mới
   * POST /api/skills
   */
  async createSkill(skillData) {
    const res = await fetch(`${API_BASE}/skills`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(skillData)
    })

    if (!res.ok) {
      let errorMsg = `Lỗi tạo Kỹ năng Agent mới (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
        else if (errJson.detail) errorMsg = errJson.detail
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return await res.json()
  },

  /**
   * Cập nhật Kỹ năng Agent hoặc bật/tắt kích hoạt
   * PUT /api/skills/:id
   */
  async updateSkill(id, skillData) {
    const res = await fetch(`${API_BASE}/skills/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(skillData)
    })

    if (!res.ok) {
      let errorMsg = `Lỗi cập nhật Kỹ năng Agent (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
        else if (errJson.detail) errorMsg = errJson.detail
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return await res.json()
  },

  /**
   * Xóa Kỹ năng Agent khỏi hệ thống
   * DELETE /api/skills/:id
   */
  async deleteSkill(id) {
    const res = await fetch(`${API_BASE}/skills/${encodeURIComponent(id)}`, {
      method: 'DELETE'
    })

    if (!res.ok && res.status !== 204) {
      let errorMsg = `Lỗi xóa Kỹ năng Agent (HTTP ${res.status})`
      try {
        const errJson = await res.json()
        if (errJson.message) errorMsg = errJson.message
      } catch {
        // ignore
      }
      throw new Error(errorMsg)
    }

    return true
  },

  /**
   * Google Research Harness² Recursive Optimization API Layer
   */
  harness: {
    /**
     * Kích hoạt chu trình Propose–Probe–Anonymized Select tối ưu hóa câu trả lời
     * POST /api/harness/optimize
     *
     * @param {Object} data
     * @param {string} [data.pageId] - ID Page áp dụng
     * @param {string} data.customerMessage - Tin nhắn gần nhất của khách
     * @param {string} data.baseResponse - Câu trả lời ban đầu của Bot cần tối ưu
     * @param {number} [data.temperatureDelta=0] - Độ sụt giảm nhiệt độ mua hàng
     * @param {string[]} [data.history=[]] - Lịch sử hội thoại dạng chuỗi
     * @param {string[]} [data.triggeredHooks=[]] - Danh sách in-loop hooks đã kích hoạt
     * @param {string} [data.triggerReason='MANUAL_1CLICK'] - Lý do kích hoạt
     * @param {string|null} [data.taskId=null] - Task ID
     * @param {number|null} [data.seed=null] - Seed kiểm thử hoán vị ngẫu nhiên
     * @returns {Promise<Object>} HarnessOptimizeResponse
     */
    async optimize(data) {
      if (!data) throw new Error('Dữ liệu yêu cầu tối ưu hóa Harness² không được để trống')

      const payload = {
        page_id: data.pageId || data.page_id || null,
        customer_message: data.customerMessage || data.customer_message || data.message || '',
        base_response: data.baseResponse || data.base_response || data.baseReply || data.base_reply || '',
        conversation_id: data.conversationId || data.conversation_id || null,
        history: Array.isArray(data.history) ? data.history : [],
        temperature_delta: data.temperatureDelta !== undefined ? Number(data.temperatureDelta) : (data.temperature_delta !== undefined ? Number(data.temperature_delta) : 0),
        triggered_hooks: Array.isArray(data.triggeredHooks) ? data.triggeredHooks : (Array.isArray(data.triggered_hooks) ? data.triggered_hooks : []),
        trigger_reason: data.triggerReason || data.trigger_reason || 'MANUAL_1CLICK',
        task_id: data.taskId || data.task_id || null,
        seed: data.seed !== undefined ? data.seed : null
      }

      if (!payload.customer_message) {
        throw new Error('customer_message không được để trống khi chạy tối ưu hóa Harness²')
      }
      if (!payload.base_response) {
        throw new Error('base_response không được để trống khi chạy tối ưu hóa Harness²')
      }

      const res = await fetch(`${API_BASE}/harness/optimize`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json'
        },
        body: JSON.stringify(payload)
      })

      if (!res.ok) {
        let errorMsg = `Lỗi tối ưu hóa Harness² (HTTP ${res.status})`
        try {
          const errJson = await res.json()
          if (errJson.message) errorMsg = errJson.message
          else if (errJson.detail) errorMsg = errJson.detail
        } catch {
          // ignore
        }
        throw new Error(errorMsg)
      }

      return await res.json()
    },

    /**
     * Đánh giá & tối ưu hóa toàn bộ cuộc hội thoại đa lượt (Whole-Conversation Harness² Audit)
     * POST /api/harness/audit-conversation
     */
    async auditConversation(data) {
      if (!data) throw new Error('Dữ liệu cuộc hội thoại không được để trống')

      const payload = {
        page_id: data.pageId || data.page_id || null,
        conversation_id: data.conversationId || data.conversation_id || null,
        customer_name: data.customerName || data.customer_name || null,
        turns: Array.isArray(data.turns) ? data.turns : [],
        auto_apply_rules: data.autoApplyRules !== undefined ? Boolean(data.autoApplyRules) : true
      }

      const res = await fetch(`${API_BASE}/harness/audit-conversation`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json'
        },
        body: JSON.stringify(payload)
      })

      if (!res.ok) {
        let errorMsg = `Lỗi đánh giá toàn hội thoại Harness² (HTTP ${res.status})`
        try {
          const errJson = await res.json()
          if (errJson.message) errorMsg = errJson.message
          else if (errJson.detail) errorMsg = errJson.detail
        } catch {
          // ignore
        }
        throw new Error(errorMsg)
      }

      return await res.json()
    },

    /**
     * Lấy danh sách lịch sử các đợt tối ưu hóa Harness²
     * GET /api/harness/runs?page_id=&verdict=&limit=
     *
     * @param {string|Object} [pageIdOrParams]
     * @param {number} [limit=20]
     * @param {string} [verdict] - 'ADOPTED' hoặc 'KEEP_BASE'
     * @returns {Promise<Array>} Danh sách HarnessRunDto
     */
    async getRuns(pageIdOrParams = null, limit = 20, verdict = null) {
      let pageId = null
      let optLimit = limit || 20
      let optVerdict = verdict || null

      if (typeof pageIdOrParams === 'object' && pageIdOrParams !== null) {
        pageId = pageIdOrParams.pageId || pageIdOrParams.page_id || null
        if (pageIdOrParams.limit !== undefined) optLimit = Number(pageIdOrParams.limit)
        if (pageIdOrParams.verdict) optVerdict = pageIdOrParams.verdict
      } else if (typeof pageIdOrParams === 'string') {
        pageId = pageIdOrParams
      }

      const searchParams = new URLSearchParams()
      if (pageId && pageId !== '__global__' && pageId !== 'ALL') {
        searchParams.set('page_id', pageId)
      }
      if (optVerdict && optVerdict !== 'ALL') {
        searchParams.set('verdict', optVerdict)
      }
      if (optLimit) {
        searchParams.set('limit', String(optLimit))
      }

      const queryStr = searchParams.toString()
      const url = queryStr ? `${API_BASE}/harness/runs?${queryStr}` : `${API_BASE}/harness/runs`

      const res = await fetch(url, {
        method: 'GET',
        headers: { Accept: 'application/json' }
      })

      if (!res.ok) {
        throw new Error(`Không thể lấy lịch sử Harness² runs (HTTP ${res.status})`)
      }

      return await res.json()
    }
  },

  // Top-level aliases
  async optimizeHarness(data) {
    return this.harness.optimize(data)
  },

  async auditConversationHarness(data) {
    return this.harness.auditConversation(data)
  },

  async getHarnessRuns(pageIdOrParams, limit, verdict) {
    return this.harness.getRuns(pageIdOrParams, limit, verdict)
  },

  /**
   * Omnichannel Conversation Inbox APIs (/api/conversations)
   */
  async getConversations(pageId = null, filter = 'ALL') {
    const searchParams = new URLSearchParams()
    if (pageId) searchParams.set('page_id', pageId)
    if (filter) searchParams.set('filter', filter)
    const res = await fetch(`${API_BASE}/conversations?${searchParams.toString()}`, {
      method: 'GET',
      headers: { Accept: 'application/json' }
    })
    if (!res.ok) {
      throw new Error(`Không thể tải danh sách hội thoại (HTTP ${res.status})`)
    }
    return await res.json()
  },

  async getConversationDetail(sessionId) {
    const res = await fetch(`${API_BASE}/conversations/${encodeURIComponent(sessionId)}`, {
      method: 'GET',
      headers: { Accept: 'application/json' }
    })
    if (!res.ok) {
      throw new Error(`Không thể tải chi tiết hội thoại (HTTP ${res.status})`)
    }
    return await res.json()
  },

  async createNewConversation({ pageId = null, customerName = '', message = '' }) {
    const res = await fetch(`${API_BASE}/conversations/new`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({
        page_id: pageId,
        customer_name: customerName,
        message
      })
    })
    if (!res.ok) {
      throw new Error(`Lỗi tạo hội thoại mới (HTTP ${res.status})`)
    }
    return await res.json()
  },

  async sendCustomerTurnInConversation(sessionId, { message = '', customerName = null }) {
    const res = await fetch(`${API_BASE}/conversations/${encodeURIComponent(sessionId)}/customer-turn`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({
        message,
        customer_name: customerName
      })
    })
    if (!res.ok) {
      throw new Error(`Lỗi gửi tin nhắn khách hàng vào hộp thư (HTTP ${res.status})`)
    }
    return await res.json()
  },

  async sendHumanSaleReplyInConversation(sessionId, { message = '', markConverted = false }) {
    const res = await fetch(`${API_BASE}/conversations/${encodeURIComponent(sessionId)}/sale-reply`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({
        message,
        mark_converted: markConverted
      })
    })
    if (!res.ok) {
      throw new Error(`Lỗi gửi tin nhắn Sale thật (HTTP ${res.status})`)
    }
    return await res.json()
  },

  async updateConversationControlMode(sessionId, { controlMode = null, status = null }) {
    const res = await fetch(`${API_BASE}/conversations/${encodeURIComponent(sessionId)}/control-mode`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({
        control_mode: controlMode,
        status
      })
    })
    if (!res.ok) {
      throw new Error(`Lỗi chuyển quyền điều khiển hội thoại (HTTP ${res.status})`)
    }
    return await res.json()
  },

  async extractRuleFromConversation(sessionId) {
    const res = await fetch(`${API_BASE}/conversations/${encodeURIComponent(sessionId)}/extract-rule`, {
      method: 'POST',
      headers: { Accept: 'application/json' }
    })
    if (!res.ok) {
      throw new Error(`Lỗi trích xuất bài học từ ca chat (HTTP ${res.status})`)
    }
    return await res.json()
  }
}



