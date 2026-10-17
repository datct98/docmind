/**
 * DocMind API Service Layer
 * Tương tác với backend Spring Boot (cổng 8090) qua /api
 */

const API_BASE = '/api'

export const api = {
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
  async submitFeedback({ documentIds, question, faultyAnswer, userCorrection, pageId = null, source = null, history = null }) {
    const payload = {
      document_ids: documentIds,
      question,
      faulty_answer: faultyAnswer,
      user_correction: userCorrection
    }
    if (pageId) payload.page_id = pageId
    if (source) payload.source = source
    if (Array.isArray(history) && history.length > 0) payload.history = history

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
  async sendFanpageBotMessage({ pageId = null, senderId = 'fb-user-sim', customerName = null, message = '', messages = null, history = [], documentIds = [], topK = 5 }) {
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
  }
}


