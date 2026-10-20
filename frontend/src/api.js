/**
 * DocMind API Service Layer
 * Tương tác với backend Spring Boot (cổng 8090) qua /api
 */

import { mockHarnessOptimize, mockHarnessConversationAudit, getMockHarnessRuns } from './mockData.js'

const API_BASE = '/api'
let _isMockMode = false

export const api = {
  /**
   * Kiểm tra / đặt trạng thái Mock Mode (Demo Mode)
   */
  isMockMode() {
    return _isMockMode
  },
  setMockMode(val) {
    _isMockMode = Boolean(val)
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
   * Upload tài liệu mới (gán trực tiếp vào Page nếu có pageId)
   * POST /api/documents
   * multipart/form-data: file, page_id
   */
  async uploadDocument(file, pageId = null) {
    const formData = new FormData()
    formData.append('file', file)
    if (pageId) {
      formData.append('page_id', pageId)
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

    const res = await fetch(`${API_BASE}/bot/fanpage/message`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(payload)
    })

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

      if (api.isMockMode()) {
        return mockHarnessOptimize(data)
      }

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

      try {
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
      } catch (err) {
        console.warn('[Harness API] optimize request failed, falling back to mock runner:', err.message || err)
        return mockHarnessOptimize(data)
      }
    },

    /**
     * Đánh giá & tối ưu hóa toàn bộ cuộc hội thoại đa lượt (Whole-Conversation Harness² Audit)
     * POST /api/harness/audit-conversation
     */
    async auditConversation(data) {
      if (!data) throw new Error('Dữ liệu cuộc hội thoại không được để trống')

      if (api.isMockMode()) {
        return mockHarnessConversationAudit(data)
      }

      const payload = {
        page_id: data.pageId || data.page_id || null,
        conversation_id: data.conversationId || data.conversation_id || null,
        customer_name: data.customerName || data.customer_name || null,
        turns: Array.isArray(data.turns) ? data.turns : [],
        auto_apply_rules: data.autoApplyRules !== undefined ? Boolean(data.autoApplyRules) : true
      }

      try {
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
      } catch (err) {
        console.warn('[Harness API] auditConversation request failed, falling back to mock runner:', err.message || err)
        return mockHarnessConversationAudit(data)
      }
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
      if (api.isMockMode()) {
        return getMockHarnessRuns(pageIdOrParams, limit, verdict)
      }

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

      try {
        const res = await fetch(url, {
          method: 'GET',
          headers: { Accept: 'application/json' }
        })

        if (!res.ok) {
          throw new Error(`Không thể lấy lịch sử Harness² runs (HTTP ${res.status})`)
        }

        return await res.json()
      } catch (err) {
        console.warn('[Harness API] getRuns request failed, falling back to mock runs:', err.message || err)
        return getMockHarnessRuns(pageIdOrParams, limit, verdict)
      }
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
  }
}



