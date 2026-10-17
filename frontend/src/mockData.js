/**
 * Dữ liệu Demo khi chưa khởi động Docker / backend Spring Boot
 */

export const initialMockPages = [
  {
    id: "page-spa-01",
    name: "Page Mỹ Phẩm & Spa Cao Cấp",
    industry: "Làm đẹp & Chăm sóc da",
    target_audience: "Nữ 25-45 tuổi, Đại lý Spa",
    description: "Chuyên tài liệu bảng giá sỉ/lẻ dược mỹ phẩm, phác đồ điều trị và chính sách chiết khấu đại lý Spa.",
    document_count: 2,
    rule_count: 2,
    created_at: new Date(Date.now() - 86400000 * 5).toISOString(),
    updated_at: new Date(Date.now() - 3600000).toISOString()
  },
  {
    id: "page-b2b-02",
    name: "Page Giải Pháp Phần Mềm B2B",
    industry: "Công nghệ & SaaS",
    target_audience: "Doanh nghiệp SME & Giám đốc IT",
    description: "Tài liệu kiến trúc hệ thống, báo giá gói Enterprise, SLA và hợp đồng triển khai phần mềm.",
    document_count: 2,
    rule_count: 1,
    created_at: new Date(Date.now() - 86400000 * 3).toISOString(),
    updated_at: new Date(Date.now() - 7200000).toISOString()
  },
  {
    id: "page-bds-03",
    name: "Page Bất Động Sản Nghỉ Dưỡng",
    industry: "Bất động sản",
    target_audience: "Nhà đầu tư cá nhân & VIP",
    description: "Bảng hàng căn hộ, chính sách chiết khấu thanh toán sớm và tiến độ hợp đồng mua bán.",
    document_count: 1,
    rule_count: 1,
    created_at: new Date(Date.now() - 86400000 * 2).toISOString(),
    updated_at: new Date(Date.now() - 18000000).toISOString()
  }
]

let demoPagesState = JSON.parse(JSON.stringify(initialMockPages))

export const getMockPages = () => {
  return [...demoPagesState]
}

export const createMockPage = (pageData) => {
  const newPage = {
    id: `page-custom-${Date.now()}`,
    name: pageData.name || 'Page Mới',
    industry: pageData.industry || '',
    target_audience: pageData.target_audience || '',
    description: pageData.description || '',
    document_count: 0,
    rule_count: 0,
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString()
  }
  demoPagesState.unshift(newPage)
  return newPage
}

export const updateMockPage = (id, pageData) => {
  const idx = demoPagesState.findIndex(p => p.id === id)
  if (idx === -1) throw new Error(`Page ${id} not found`)
  demoPagesState[idx] = {
    ...demoPagesState[idx],
    ...pageData,
    updated_at: new Date().toISOString()
  }
  return demoPagesState[idx]
}

export const deleteMockPage = (id) => {
  demoPagesState = demoPagesState.filter(p => p.id !== id)
  return true
}

export const initialMockDocuments = [
  {
    document_id: "doc-sample-spa-price-01",
    filename: "Bang_Gia_Si_Duoc_My_Pham_2026.xlsx",
    status: "READY",
    pages: 12,
    page_id: "page-spa-01",
    engine_breakdown: {
      xlsx_office: 12
    },
    created_at: new Date(Date.now() - 1800000).toISOString()
  },
  {
    document_id: "doc-sample-spa-policy-02",
    filename: "Chinh_Sach_Dai_Ly_Spa_Toan_Quoc.docx",
    status: "READY",
    pages: 16,
    page_id: "page-spa-01",
    engine_breakdown: {
      docx_office: 16
    },
    created_at: new Date(Date.now() - 3200000).toISOString()
  },
  {
    document_id: "doc-sample-ai-arch-01",
    filename: "DocMind-Architecture-Specification.pdf",
    status: "READY",
    pages: 18,
    page_id: "page-b2b-02",
    engine_breakdown: {
      pymupdf: 12,
      docling: 6
    },
    created_at: new Date(Date.now() - 3600000).toISOString()
  },
  {
    document_id: "doc-sample-financial-02",
    filename: "Bao_Cao_Gia_Goi_SaaS_Enterprise_Q3.docx",
    status: "READY",
    pages: 34,
    page_id: "page-b2b-02",
    engine_breakdown: {
      docx_office: 34
    },
    created_at: new Date(Date.now() - 7200000).toISOString()
  },
  {
    document_id: "doc-sample-scan-ocr-03",
    filename: "Chinh_Sach_Thanh_Toan_Can_Ho_Bien.md",
    status: "READY",
    pages: 5,
    page_id: "page-bds-03",
    engine_breakdown: {
      markdown_parser: 5
    },
    created_at: new Date(Date.now() - 120000).toISOString()
  }
]

/**
 * Kho quy tắc kinh nghiệm mẫu (AI Playbook)
 */
export const initialMockRules = [
  {
    id: "rule-sales-pricing-01",
    category: "PRICING",
    page_id: "page-spa-01",
    trigger_condition: "Tra cứu bảng giá bán buôn, chiết khấu đại lý Spa hoặc đơn giá chiết khấu",
    root_cause: "Nhầm lẫn giữa giá Gross (đã gồm VAT 10%) và giá Net (trước VAT) khi tính toán chiết khấu bán hàng",
    generalized_rule: "Khi tính toán báo giá đại lý trong bảng giá hoặc văn bản kinh doanh, luôn dùng giá Net trước VAT làm gốc tính chiết khấu và đối chiếu điều kiện ngày hiệu lực.",
    retrieval_keywords: ["bảng giá", "chiết khấu đại lý", "giá Net", "VAT", "đơn giá"],
    active: true,
    created_at: new Date(Date.now() - 172800000).toISOString()
  },
  {
    id: "rule-discount-tier-02",
    category: "DISCOUNT",
    page_id: "page-spa-01",
    trigger_condition: "Áp dụng chiết khấu bậc thang theo số lượng hoặc mốc doanh thu đơn hàng Spa",
    root_cause: "Áp dụng tỷ lệ chiết khấu cao nhất của bậc trên mà không kiểm tra điều kiện doanh số tối thiểu (MOQ threshold)",
    generalized_rule: "Khi áp dụng chính sách chiết khấu bậc thang, phải đối chiếu số lượng đơn hàng với từng mốc ngưỡng doanh số tối thiểu quy định trước khi lấy tỷ lệ % chiết khấu tương ứng.",
    retrieval_keywords: ["chiết khấu bậc thang", "ngưỡng doanh số", "MOQ", "tỷ lệ chiết khấu", "bậc thang"],
    active: true,
    created_at: new Date(Date.now() - 86400000).toISOString()
  },
  {
    id: "rule-excel-window-03",
    category: "GENERAL",
    page_id: null,
    trigger_condition: "Tổng hợp số liệu từ các sheet Excel lớn có nhiều phân đoạn bảng tính",
    root_cause: "Đọc thiếu dòng header cột ở các phân đoạn bảng tính bị chia nhỏ, dẫn tới đối chiếu nhầm cột doanh thu thực tế",
    generalized_rule: "Khi đọc bảng tính Excel nhiều hàng, luôn đối chiếu dòng tiêu đề cột (Header row) của từng sheet để xác định chính xác trường dữ liệu cần trích xuất.",
    retrieval_keywords: ["Excel", "tiêu đề cột", "bảng tính", "doanh thu", "header row"],
    active: true,
    created_at: new Date(Date.now() - 259200000).toISOString()
  },
  {
    id: "rule-b2b-sla-04",
    category: "POLICY",
    page_id: "page-b2b-02",
    trigger_condition: "Tư vấn điều khoản cam kết SLA và chi phí bản quyền phần mềm B2B",
    root_cause: "Báo giá gói Standard nhưng áp dụng điều khoản cam kết uptime 99.99% chỉ dành riêng cho gói Enterprise",
    generalized_rule: "Khi tư vấn gói phần mềm doanh nghiệp, phải đối chiếu đúng cấp độ gói (Standard/Pro/Enterprise) với bảng quyền lợi SLA tương ứng, không gộp quyền lợi gói cao cho gói thấp.",
    retrieval_keywords: ["SLA", "Enterprise", "bản quyền", "gói phần mềm"],
    active: true,
    created_at: new Date(Date.now() - 320000000).toISOString()
  },
  {
    id: "rule-contract-policy-05",
    category: "POLICY",
    page_id: "page-bds-03",
    trigger_condition: "Tra cứu tiến độ thanh toán và điều khoản tạm ứng trong hợp đồng bất động sản",
    root_cause: "Gộp chung điều khoản đặt cọc giữ chỗ với đợt thanh toán ký hợp đồng mua bán đợt 1",
    generalized_rule: "Trong hợp đồng mua bán bất động sản, tách biệt rõ ràng khoản tiền đặt cọc giữ chỗ ban đầu với tỷ lệ thanh toán theo từng mốc tiến độ xây dựng và bàn giao sổ.",
    retrieval_keywords: ["hợp đồng", "đặt cọc", "tiến độ thanh toán", "bàn giao"],
    active: true,
    created_at: new Date(Date.now() - 432000000).toISOString()
  }
]

// Mutable array for demo mode in-memory state
let demoRulesState = JSON.parse(JSON.stringify(initialMockRules))

export const getMockRules = (params = {}) => {
  let result = [...demoRulesState]
  if (params.pageId) {
    if (params.pageId === '__global__') {
      result = result.filter(r => !r.page_id)
    } else {
      const includeGlobal = params.includeGlobal !== false
      result = result.filter(r => r.page_id === params.pageId || (includeGlobal && !r.page_id))
    }
  }
  if (params.category) {
    result = result.filter(r => r.category === params.category)
  }
  if (params.active !== undefined && params.active !== null && params.active !== '') {
    const isActive = params.active === true || params.active === 'true'
    result = result.filter(r => r.active === isActive)
  }
  if (params.search) {
    const s = params.search.toLowerCase()
    result = result.filter(r => 
      (r.generalized_rule && r.generalized_rule.toLowerCase().includes(s)) ||
      (r.root_cause && r.root_cause.toLowerCase().includes(s)) ||
      (r.trigger_condition && r.trigger_condition.toLowerCase().includes(s)) ||
      (r.retrieval_keywords && r.retrieval_keywords.some(k => k.toLowerCase().includes(s)))
    )
  }
  return result
}

export const createMockRule = (ruleData) => {
  const newRule = {
    id: `rule-custom-${Date.now()}`,
    category: ruleData.category || 'GENERAL',
    page_id: ruleData.page_id || null,
    trigger_condition: ruleData.trigger_condition || 'Quy tắc bổ sung thủ công',
    root_cause: ruleData.root_cause || 'Bổ sung theo chính sách bán hàng mới',
    generalized_rule: ruleData.generalized_rule || '',
    retrieval_keywords: Array.isArray(ruleData.retrieval_keywords) 
      ? ruleData.retrieval_keywords 
      : (ruleData.retrieval_keywords ? ruleData.retrieval_keywords.split(',').map(s => s.trim()).filter(Boolean) : []),
    active: ruleData.active !== undefined ? ruleData.active : true,
    created_at: new Date().toISOString()
  }
  demoRulesState.unshift(newRule)
  return newRule
}

export const updateMockRule = (id, ruleData) => {
  const index = demoRulesState.findIndex(r => r.id === id)
  if (index === -1) {
    throw new Error(`Rule ${id} not found in mock store`)
  }
  const updatedKeywords = ruleData.retrieval_keywords !== undefined
    ? (Array.isArray(ruleData.retrieval_keywords)
        ? ruleData.retrieval_keywords
        : ruleData.retrieval_keywords.split(',').map(s => s.trim()).filter(Boolean))
    : demoRulesState[index].retrieval_keywords

  demoRulesState[index] = {
    ...demoRulesState[index],
    ...ruleData,
    retrieval_keywords: updatedKeywords
  }
  return demoRulesState[index]
}

export const deleteMockRule = (id) => {
  demoRulesState = demoRulesState.filter(r => r.id !== id)
  return true
}

/**
 * Giả lập "Báo sai & Dạy AI" trong Demo Mode
 */
export const mockSubmitFeedback = ({ question, faultyAnswer, userCorrection, documentIds, pageId = null, source = null, history = [] }) => {
  // Trích xuất phân loại dựa trên ngữ cảnh
  let category = 'GENERAL'
  const text = (question + ' ' + userCorrection).toLowerCase()
  if (text.includes('giá') || text.includes('price') || text.includes('đơn giá') || text.includes('bảng giá')) {
    category = 'PRICING'
  } else if (text.includes('chiết khấu') || text.includes('discount') || text.includes('bậc thang')) {
    category = 'DISCOUNT'
  } else if (text.includes('hợp đồng') || text.includes('điều khoản') || text.includes('chính sách') || text.includes('quy định')) {
    category = 'POLICY'
  }

  // Khái quát hóa quy tắc suy luận
  const learnedRule = {
    id: `rule-learned-${Date.now()}`,
    category,
    page_id: pageId || null,
    trigger_condition: `Khi người dùng hỏi về: "${question.length > 50 ? question.slice(0, 50) + '...' : question}"`,
    root_cause: `AI đã diễn giải thiếu chuẩn xác ngữ cảnh: "${faultyAnswer.length > 60 ? faultyAnswer.slice(0, 60) + '...' : faultyAnswer}"`,
    generalized_rule: userCorrection.trim().length > 0 
      ? `Nguyên tắc suy luận: ${userCorrection.trim()}`
      : 'Luôn kiểm tra kỹ các điều kiện ràng buộc trong tài liệu bán hàng trước khi đưa ra kết luận số liệu.',
    retrieval_keywords: [category.toLowerCase(), 'quy tắc bán hàng', 'đối chiếu tài liệu', 'sales policy'],
    active: true,
    created_at: new Date().toISOString()
  }

  // Thêm vào kho quy tắc mock
  demoRulesState.unshift(learnedRule)

  // Tự động tiến hóa / đồng bộ vào Kho Kỹ năng Agent (demoSkillsState)
  const cleanCorrection = userCorrection.replace(/(sai rồi|không đúng|nhầm rồi)[,:\s]*/gi, '').trim()
  let skillCategory = 'SALES_STRATEGY'
  if (/xưng hô|anh|chị|ngắn gọn|bong bóng|giọng điệu|lịch sự/.test(text)) {
    skillCategory = 'COMMUNICATION'
  } else if (/tâm lý|thấu cảm|đồng cảm|sợ đau|lâu năm|rủi ro|bảo hành|nam giới|phòng riêng/.test(text)) {
    skillCategory = 'PSYCHOLOGY'
  }
  const normalizedQ = question.toLowerCase().replace(/[^a-z0-9\u00C0-\u1EF9]+/gi, '_').replace(/^_|_$/g, '').slice(0, 18).toUpperCase() || 'FEEDBACK'
  const evolvedCode = `LEARNED_${skillCategory.slice(0, 5)}_${normalizedQ}`
  const triggerTokens = question
    .toLowerCase()
    .replace(/[,.?!:;()"']/g, ' ')
    .split(/\s+/)
    .filter(w => w.length >= 2)
    .slice(0, 6)
    .join(', ')

  const existingSkillIdx = demoSkillsState.findIndex(
    s => s.code === evolvedCode && (s.page_id || null) === (pageId || null)
  )
  const nowIso = new Date().toISOString()
  const syncedSkill = {
    id: existingSkillIdx >= 0 ? demoSkillsState[existingSkillIdx].id : `skill-learned-${Date.now()}`,
    code: evolvedCode,
    name: `Kỹ năng Thực chiến: ${cleanCorrection.length > 42 ? cleanCorrection.slice(0, 42) + '...' : cleanCorrection}`,
    category: skillCategory,
    description: `Đúc kết tự động từ phản hồi thực chiến trên câu hỏi: "${question}"`,
    trigger_signals: triggerTokens || 'tư vấn, khách hỏi, báo giá',
    tactical_instructions: cleanCorrection,
    good_example: `Áp dụng đúng chỉ dẫn: "${cleanCorrection}"`,
    anti_pattern: faultyAnswer ? `Tránh trả lời như cũ: "${faultyAnswer.slice(0, 90)}"` : '',
    priority: 93,
    active: true,
    page_id: pageId || null,
    created_at: existingSkillIdx >= 0 ? demoSkillsState[existingSkillIdx].created_at : nowIso,
    updated_at: nowIso
  }
  if (existingSkillIdx >= 0) {
    demoSkillsState[existingSkillIdx] = syncedSkill
  } else {
    demoSkillsState.unshift(syncedSkill)
  }

  // Tạo câu trả lời mới đã được cải thiện
  let regeneratedAnswer = ''
  if (source === 'FANPAGE_BOT') {
    const botSim = mockFanpageBotReply({
      message: question,
      history: Array.isArray(history) ? history : [],
      pageId
    })
    const honorific = botSim.customer_insight?.honorific || 'Chị'
    regeneratedAnswer = `Dạ em xin phép cập nhật thông tin chuẩn xác nhất gửi ${honorific} ạ: ${cleanCorrection}\n\n${botSim.reply_text}`
    const mergedSkills = [
      syncedSkill,
      ...(botSim.active_skills || []).filter(s => s.code !== syncedSkill.code)
    ].slice(0, 3)

    return {
      learned_rule: learnedRule,
      regenerated_answer: regeneratedAnswer,
      applied_rules: [learnedRule, ...(botSim.applied_rules || []).filter(r => r.id !== learnedRule.id)],
      customer_insight: botSim.customer_insight,
      active_skills: mergedSkills
    }
  } else {
    regeneratedAnswer = `**Đã hiệu chỉnh & đúc kết quy tắc:**\n\nCảm ơn bạn đã chỉ dẫn! Dựa trên nguyên tắc: *" ${learnedRule.generalized_rule} "*,\n\nCâu trả lời chính xác được cập nhật như sau:\n- **Nội dung điều chỉnh**: ${userCorrection}\n- **Quy tắc đúc kết**: Đã được lưu vào Bộ nhớ Kinh nghiệm AI (**AI Playbook**) và sẽ tự động áp dụng vào các câu hỏi tương tự.`
  }

  return {
    learned_rule: learnedRule,
    regenerated_answer: regeneratedAnswer,
    applied_rules: [learnedRule]
  }
}

/**
 * Giả lập Phản hồi Chat kèm phát hiện Conversational Correction & Applied Rules
 */
export const mockChatAnswer = ({ question, previousQuestion, previousAnswer, pageId = null }) => {
  const qLower = question.toLowerCase()

  // 1. Kiểm tra xem câu hỏi có phải là Conversational Correction không
  const isCorrection = 
    qLower.includes('sai rồi') || 
    qLower.includes('không đúng') || 
    qLower.includes('chưa đúng') || 
    qLower.includes('nhầm lẫn') || 
    qLower.includes('phải là') ||
    qLower.includes('sửa lại') ||
    qLower.includes('bị sai')

  let newlyLearnedRule = null
  if (isCorrection) {
    let cat = 'PRICING'
    if (qLower.includes('chiết khấu')) cat = 'DISCOUNT'
    else if (qLower.includes('hợp đồng') || qLower.includes('chính sách')) cat = 'POLICY'

    newlyLearnedRule = {
      id: `rule-conv-${Date.now()}`,
      category: cat,
      page_id: pageId || null,
      trigger_condition: `Hiệu chỉnh từ phản hồi hội thoại của người dùng: "${question}"`,
      root_cause: previousAnswer ? `Câu trả lời trước đó chưa chính xác với yêu cầu nghiệp vụ` : `Nhận định chưa đúng ngữ cảnh bán hàng`,
      generalized_rule: `Theo hiệu chỉnh của người dùng: ${question.replace(/(sai rồi|không đúng|chưa đúng|phải là|nhầm|sửa lại)[,:\s]*/gi, '').trim() || 'Luôn đối chiếu kỹ điều kiện số liệu và quy định bán hàng.'}`,
      retrieval_keywords: ['hiệu chỉnh hội thoại', cat.toLowerCase(), 'sales rule'],
      active: true,
      created_at: new Date().toISOString()
    }
    // Lưu vào kho mock
    demoRulesState.unshift(newlyLearnedRule)
  }

  // 2. Tìm các quy tắc active có liên quan (scoped theo pageId + global rules)
  const activeRules = demoRulesState.filter(r => r.active && (!pageId || !r.page_id || r.page_id === pageId))
  const appliedRules = activeRules.filter(r => {
    return r.retrieval_keywords.some(k => qLower.includes(k.toLowerCase())) ||
           (r.generalized_rule && r.generalized_rule.toLowerCase().includes(qLower))
  }).slice(0, 3)

  // 3. Nội dung trả lời
  let answerText = ''
  let citations = []

  if (isCorrection) {
    answerText = `Cảm ơn bạn đã hiệu chỉnh trực tiếp trong hội thoại! Tôi đã ghi nhận và đúc kết quy tắc mới vào **Bộ nhớ Kinh nghiệm AI (AI Playbook)**.\n\nTheo quy tắc chuẩn hóa: *${newlyLearnedRule.generalized_rule}*.\n\nThông tin đã được đồng bộ để phục vụ cho các câu hỏi tiếp theo trong phiên làm việc.`
    citations = [
      {
        chunk_id: "chk-correction-01",
        document_id: "doc-sample-financial-02",
        block_ids: ["blk-corr-1"],
        page: 1,
        bbox: [50.0, 100.0, 520.0, 220.0],
        heading_path: ["Hiệu chỉnh nghiệp vụ", "Quy tắc Playbook"],
        snippet: "Người dùng hiệu chỉnh thông tin bán hàng trực tiếp qua hội thoại; AI tự động trích xuất meta-rule và lưu vào vector store."
      }
    ]
  } else if (qLower.includes('kiến trúc') || qLower.includes('thành phần') || qLower.includes('cổng')) {
    answerText = sampleQA['kiến trúc'].answer
    citations = sampleQA['kiến trúc'].citations
  } else {
    answerText = sampleQA['default'].answer
    if (appliedRules.length > 0) {
      answerText += `\n\n*(Đã tối ưu câu trả lời dựa trên ${appliedRules.length} quy tắc từ AI Playbook: ${appliedRules.map(r => `[${r.category}]`).join(' ')})*`
    }
    citations = sampleQA['default'].citations
  }

  return {
    answer: answerText,
    citations,
    applied_rules: appliedRules,
    newly_learned_rule: newlyLearnedRule
  }
}

export const sampleQA = {
  "kiến trúc": {
    answer: "DocMind là hệ thống RAG độc lập dựa trên tài liệu (phong cách NotebookLLM). Kiến trúc bao gồm hai dịch vụ chính và PostgreSQL:\n\n1. **`api-java` (Port 8090)**: Orchestrator viết bằng Spring Boot 4, quản lý vòng đời ingest, chunking, hybrid retrieval (pgvector + BM25 Reciprocal Rank Fusion), gọi LLM và xác thực trích dẫn (citation validation).\n2. **`doc-service-py` (Port 8091)**: FastAPI parser trích xuất các block chuẩn hóa từ PDF, DOCX, PPTX với PyMuPDF, Docling và PaddleOCR.\n3. **`PostgreSQL 16`**: Lưu trữ vector embedding và schema chuẩn hóa qua Flyway.\n\nĐặc biệt, luồng chat hoàn toàn chạy trên Java và **không bao giờ gọi sang Python service** lúc suy luận.",
    citations: [
      {
        chunk_id: "chk-arch-001",
        document_id: "doc-sample-ai-arch-01",
        block_ids: ["blk-01", "blk-02"],
        page: 1,
        bbox: [72.0, 100.5, 540.0, 320.0],
        heading_path: ["Architecture", "Overview"],
        snippet: "Two services plus Postgres: api-java (8090) orchestrator and chat/RAG brain; doc-service-py (8091) FastAPI parser."
      },
      {
        chunk_id: "chk-arch-002",
        document_id: "doc-sample-ai-arch-01",
        block_ids: ["blk-05"],
        page: 2,
        bbox: [72.0, 450.0, 520.0, 590.0],
        heading_path: ["Architecture", "Chat Path"],
        snippet: "The chat path is Java-only. During chat, api-java reads already-indexed data and never calls doc-service-py."
      }
    ]
  },
  "default": {
    answer: "📋 **[TRA CỨU CHUẨN XÁC & LƯU Ý NỘI BỘ]**\n- **Liệu trình Trị Nám Laser Picosure Pro (Mã: SP01)**: Đơn giá `1.500.000 VNĐ/buổi` (Liệu trình chuẩn 5 buổi = `7.500.000 VNĐ`), tặng kèm **01 Kem chống nắng Y khoa trị giá 450.000 VNĐ** [1].\n- **Gói Trẻ Hóa Da Hifu (Mã: SP02)**: Đơn giá `8.500.000 VNĐ/gói`, tặng **02 buổi đắp mặt nạ Collagen vàng 24K** [1].\n- **Lưu ý chiết khấu & loại trừ**: Đăng ký Combo 5 buổi được giảm **10%** (còn `6.750.000 VNĐ`); tuyệt đối không áp dụng giảm giá cho Dược mỹ phẩm mua lẻ (Mã: `SP03`) [1].\n\n💬 **[TIN NHẮN MẪU GỬI KHÁCH - SALE READY]**\nDạ em chào Chị ạ! Hiện tại bên em đang có ưu đãi rất tốt cho 2 dịch vụ Chị đang quan tâm ạ:\n- Khi Chị đăng ký **Liệu trình Trị Nám Laser Picosure Pro (5 buổi)**, bên em gửi tặng ngay Chị **01 Kem chống nắng Y khoa trị giá 450.000đ** và ưu đãi giảm **10% Combo** (chỉ còn `6.750.000đ` trọn gói 5 buổi, giá lẻ `1.500.000đ/buổi`) ạ.\n- Với **Gói Trẻ Hóa Da Hifu (1 buổi - 8.500.000đ)**, Chị sẽ được tặng kèm **02 buổi đắp mặt nạ Collagen vàng 24K** giúp phục hồi da căng bóng ngay sau làm.\n\nĐặc biệt bên em cam kết 100% Bác sĩ Da liễu trực tiếp thăm khám, **không phát sinh bất kỳ phụ phí nào và không nhận tiền Tip** ạ. Chị đang quan tâm cải thiện vùng nám hay nâng cơ trẻ hóa trước để em đặt lịch Bác sĩ soi da miễn phí cho Chị vào cuối tuần này nhé ạ?",
    citations: [
      {
        chunk_id: "chk-gen-01",
        document_id: "doc-sample-ai-arch-01",
        block_ids: ["blk-10"],
        page: 1,
        bbox: [50.0, 120.0, 500.0, 240.0],
        heading_path: ["2. BẢNG GIÁ SẢN PHẨM & GÓI DỊCH VỤ", "3. CHÍNH SÁCH CHIẾT KHẤU"],
        snippet: "SP01 | Liệu trình Trị Nám Laser Picosure Pro (5 buổi) | 1.500.000 VNĐ/buổi | Tặng 01 Kem chống nắng Y khoa trị giá 450.000 VNĐ. Combo 5 buổi giảm 10%."
      }
    ]
  }
}

/**
 * Kho Kỹ năng Agent mẫu (Agent Skill Registry)
 */
export const initialMockSkills = [
  {
    id: 'skill-psych-01',
    code: 'PSYCH_CHRONIC_EMPATHY',
    name: 'Thấu cảm sâu Nỗi đau Mãn tính & Lâu năm',
    category: 'PSYCHOLOGY',
    description: 'Đọc vị tâm lý khách hàng bị mụn/nám/lão hóa lâu năm đã chữa nhiều nơi không khỏi.',
    trigger_signals: 'lâu năm, mãn tính, chữa nhiều nơi, không khỏi, tái phát, sẹo rỗ, nám chân sâu',
    tactical_instructions: 'Đồng cảm sâu sắc với hành trình gian nan của khách. Phân tách trung thực phần gói tiêu chuẩn cải thiện được và phần cần Bác sĩ soi da AI 3D đánh giá riêng.',
    good_example: 'Dạ em rất hiểu cảm giác của Chị khi đã kiên trì điều trị nhiều nơi mà chưa cải thiện dứt điểm ạ...',
    anti_pattern: 'Tuyệt đối không hứa cam kết sạch 100% sau vài buổi khi chưa soi da.',
    priority: 95,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 5).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 2).toISOString()
  },
  {
    id: 'skill-psych-02',
    code: 'PSYCH_ZERO_RISK_REVERSAL',
    name: 'Đảo ngược Rủi ro về 0% (Zero-Risk Reversal)',
    category: 'PSYCHOLOGY',
    description: 'Xóa bỏ nỗi sợ mất tiền oan, sợ đau rát, sợ chèo kéo hoặc phát sinh chi phí ẩn.',
    trigger_signals: 'sợ đau, sưng đỏ, nghỉ dưỡng, bảo hành, cam kết, tái phát, phụ phí, tiền tip',
    tactical_instructions: 'Nhấn mạnh cam kết bảo hành bằng văn bản, 100% Bác sĩ trực tiếp thực hiện, trọn gói 0 phát sinh phụ phí & 0 nhận tiền Tip.',
    good_example: 'Dạ bên em có cam kết bảo hành bằng văn bản và hoàn toàn không phát sinh phụ phí hay nhận tiền Tip nên Chị yên tâm tuyệt đối nhé ạ.',
    anti_pattern: 'Không trả lời chung chung "không sao đâu chị" mà thiếu cam kết bảo chứng.',
    priority: 92,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 5).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 2).toISOString()
  },
  {
    id: 'skill-psych-03',
    code: 'PSYCH_DISCREET_VIP_CARE',
    name: 'Tâm lý Tinh tế cho Khách Nam / Trung niên / VIP Kín đáo',
    category: 'PSYCHOLOGY',
    description: 'Phục vụ khách hàng Nam giới, trung niên hoặc khách cần không gian riêng tư kín đáo.',
    trigger_signals: 'nam giới, khách nam, đàn ông, trung niên, phòng riêng, phòng vip, kín đáo, cặp đôi',
    tactical_instructions: 'Giữ giọng văn điềm đạm, tôn trọng, khẳng định hiệu quả khoa học và nhấn mạnh không gian phòng riêng VIP kín đáo.',
    good_example: 'Dạ liệu trình bên em được thiết kế rất phù hợp cho nền da Nam giới với phòng VIP riêng tư, kín đáo ạ.',
    anti_pattern: 'Tránh dùng từ ngữ quá điệu đà hoặc làm khách nam cảm thấy ngại ngùng.',
    priority: 90,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 4).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 2).toISOString()
  },
  {
    id: 'skill-sales-01',
    code: 'SALES_SPIN_DIAGNOSIS',
    name: 'Chẩn đoán Chuyên gia SPIN trước khi Báo giá',
    category: 'SALES_STRATEGY',
    description: 'Đặt câu hỏi khai thác tình trạng (SPIN Selling) như một chuyên gia thực thụ thay vì chỉ trả lời cộc lốc.',
    trigger_signals: 'tư vấn, bị mụn, bị nám, da khô, lão hóa, dịch vụ, gói nào',
    tactical_instructions: 'Giải đáp ngắn gọn câu hỏi của khách rồi đặt đúng 1 câu hỏi phân loại nhẹ nhàng về tình trạng hoặc thời gian bị để cá nhân hóa phác đồ.',
    good_example: 'Hiện tại tình trạng nám của Chị xuất hiện lâu chưa hay mới bị gần đây để em tư vấn phác đồ sát nhất cho Chị nhé ạ?',
    anti_pattern: 'Không hỏi dồn dập 2-3 câu hỏi cùng lúc như hỏi cung.',
    priority: 88,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 4).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 1).toISOString()
  },
  {
    id: 'skill-sales-02',
    code: 'SALES_VALUE_FRAMING_PRICE',
    name: 'Neo Giá trị & Quyền lợi trước khi Báo giá (Value Stacking)',
    category: 'SALES_STRATEGY',
    description: 'Xử lý khéo léo khi khách hỏi giá hoặc chê đắt bằng cách neo giá trị Y khoa và quyền lợi đi kèm trước con số.',
    trigger_signals: 'giá, bao nhiêu, chi phí, đắt, cao thế, chiết khấu, khuyến mãi, combo',
    tactical_instructions: 'Nêu rõ quyền lợi Bác sĩ Da liễu + Bảo hành văn bản + Cam kết 0 phụ phí/0 Tip trước khi đưa ra mức giá niêm yết và bài toán tiết kiệm Combo.',
    good_example: 'Dạ với Phác đồ Trị Nám Laser Pico có Bác sĩ Da liễu trực tiếp thực hiện và bảo hành 12 tháng bằng văn bản, mức giá trọn gói là 4.500.000đ/buổi ạ.',
    anti_pattern: 'Không ném mỗi con số giá khô khan mà không nêu quyền lợi đi kèm.',
    priority: 94,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 4).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 1).toISOString()
  },
  {
    id: 'skill-sales-03',
    code: 'SALES_MICRO_COMMITMENT_CLOSE',
    name: 'Chốt hẹn Cam kết nhỏ 0đ Rủi ro (Micro-Commitment Close)',
    category: 'SALES_STRATEGY',
    description: 'Chuyển hóa khách đang cân nhắc thành lịch hẹn ghé thăm khám / soi da miễn phí không áp lực mua.',
    trigger_signals: 'đặt lịch, suy nghĩ thêm, cân nhắc, cuối tuần, rảnh, thử trước',
    tactical_instructions: 'Mời khách ghé trải nghiệm suất Bác sĩ soi da AI 3D miễn phí 0đ hoặc buổi lẻ trải nghiệm, kèm câu hỏi lựa chọn khung giờ (Sáng hay Chiều).',
    good_example: 'Chị muốn em giữ suất Bác sĩ soi da AI 3D miễn phí vào sáng hay chiều cuối tuần này để Bác sĩ kiểm tra nền da trước cho Chị nhé ạ?',
    anti_pattern: 'Không ép khách chốt gói tiền triệu ngay trên khung chat khi khách còn lưỡng lự.',
    priority: 89,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 3).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 1).toISOString()
  },
  {
    id: 'skill-comm-01',
    code: 'COMM_MIRROR_AND_PACE',
    name: 'Đồng điệu Xưng hô & Nhịp điệu Hội thoại (Mirroring & Pacing)',
    category: 'COMMUNICATION',
    description: 'Tự động khớp xưng hô Anh/Chị theo khách hàng và điều chỉnh nhịp độ tin nhắn tự nhiên trên Messenger.',
    trigger_signals: 'anh, chị, em, mình, tư vấn nhanh, ngắn gọn',
    tactical_instructions: 'Giữ nhất quán xưng hô "em" - "Anh/Chị" xuyên suốt cuộc trò chuyện. Tách ý thành 2 bong bóng chat ngắn gọn, dễ đọc trên điện thoại.',
    good_example: 'Dạ em chào Anh ạ! Em xin gửi thông tin chi tiết để Anh dễ tham khảo nhé ạ.',
    anti_pattern: 'Tuyệt đối không gọi nhầm khách Nam là Chị hoặc viết một đoạn văn dài đặc chữ.',
    priority: 86,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 3).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 1).toISOString()
  },
  {
    id: 'skill-comm-02',
    code: 'COMM_NO_REPEAT_MEMORY',
    name: 'Ghi nhớ Xuyên suốt — Không hỏi lại Dữ kiện đã nói',
    category: 'COMMUNICATION',
    description: 'Ghi nhớ toàn bộ triệu chứng, nỗi đau và SĐT khách đã cung cấp ở các lượt trước.',
    trigger_signals: 'như đã nói, chị nói rồi, anh vừa bảo, số điện thoại trên',
    tactical_instructions: 'Nối tiếp mượt mà vào những gì khách đã chia sẻ ở lịch sử chat; tuyệt đối không chào lại từ đầu hoặc hỏi lại tình trạng khách đã nói.',
    good_example: 'Dạ với nền da nhạy cảm và tình trạng nám 3 năm như Chị vừa chia sẻ, Bác sĩ bên em sẽ điều chỉnh bước sóng êm dịu nhất ạ.',
    anti_pattern: 'Không hỏi lại "Da chị đang gặp vấn đề gì ạ?" khi khách đã kể ở câu trước.',
    priority: 91,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 3).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 1).toISOString()
  }
]

let demoSkillsState = JSON.parse(JSON.stringify(initialMockSkills))

export const getMockSkills = (params = {}) => {
  let result = [...demoSkillsState]
  if (params.pageId) {
    if (params.pageId === '__global__') {
      result = result.filter(s => !s.page_id)
    } else {
      const includeGlobal = params.includeGlobal !== false
      result = result.filter(s => s.page_id === params.pageId || (includeGlobal && !s.page_id))
    }
  }
  if (params.category) {
    result = result.filter(s => s.category === params.category)
  }
  if (params.active !== undefined && params.active !== null && params.active !== '') {
    const isActive = params.active === true || params.active === 'true'
    result = result.filter(s => s.active === isActive)
  }
  if (params.search) {
    const q = params.search.toLowerCase()
    result = result.filter(s =>
      (s.name && s.name.toLowerCase().includes(q)) ||
      (s.code && s.code.toLowerCase().includes(q)) ||
      (s.description && s.description.toLowerCase().includes(q)) ||
      (s.trigger_signals && s.trigger_signals.toLowerCase().includes(q)) ||
      (s.tactical_instructions && s.tactical_instructions.toLowerCase().includes(q))
    )
  }
  return result.sort((a, b) => (b.priority || 0) - (a.priority || 0))
}

export const createMockSkill = (skillData) => {
  const now = new Date().toISOString()
  const newSkill = {
    id: `skill-custom-${Date.now()}`,
    code: skillData.code || `CUSTOM_${Date.now().toString().slice(-4)}`,
    name: skillData.name || 'Kỹ năng mới',
    category: skillData.category || 'SALES_STRATEGY',
    description: skillData.description || '',
    trigger_signals: skillData.trigger_signals || '',
    tactical_instructions: skillData.tactical_instructions || '',
    good_example: skillData.good_example || '',
    anti_pattern: skillData.anti_pattern || '',
    priority: Number(skillData.priority) || 80,
    active: skillData.active !== undefined ? Boolean(skillData.active) : true,
    page_id: skillData.page_id || null,
    created_at: now,
    updated_at: now
  }
  demoSkillsState.unshift(newSkill)
  return newSkill
}

export const updateMockSkill = (id, skillData) => {
  const idx = demoSkillsState.findIndex(s => s.id === id)
  if (idx === -1) throw new Error(`Skill ${id} not found`)
  demoSkillsState[idx] = {
    ...demoSkillsState[idx],
    ...skillData,
    priority: skillData.priority !== undefined ? Number(skillData.priority) : demoSkillsState[idx].priority,
    updated_at: new Date().toISOString()
  }
  return demoSkillsState[idx]
}

export const deleteMockSkill = (id) => {
  demoSkillsState = demoSkillsState.filter(s => s.id !== id)
  return true
}

/**
 * Giả lập phản hồi API Bot Trực Fanpage Facebook (/api/bot/fanpage/message) trong Demo Mode
 */
export const mockFanpageBotReply = ({ message = '', messages = null, history = [], customerName = null, pageId = null }) => {
  const merged = messages && messages.length > 0 ? messages.join('. ') : message
  const lower = merged.toLowerCase()

  // Match applied rules from active rules (page-scoped + global)
  const activeRules = demoRulesState.filter(r => r.active && (!pageId || !r.page_id || r.page_id === pageId))
  const appliedRules = activeRules.filter(r => {
    return (r.retrieval_keywords && r.retrieval_keywords.some(k => lower.includes(k.toLowerCase()))) ||
           (r.generalized_rule && r.generalized_rule.toLowerCase().includes(lower))
  }).slice(0, 3)

  const phoneMatch = merged.match(/(?:\+84|84|0)(?:3[2-9]|5[25689]|7[06-9]|8[1-9]|9[0-46-9])(?:[.\-\s]?\d){7}\b/)
  const phone = phoneMatch ? phoneMatch[0].replace(/[.\-\s]/g, '') : null
  const timeMatch = merged.match(/((sáng|chiều|tối)\s+(nay|mai|mốt|thứ\s+[2-7]|chủ\s+nhật|cuối\s+tuần)|thứ\s+[2-7]\s+tuần\s+này|cuối\s+tuần\s+này|\d{1,2}\s*(h|giờ)(\s*\d{1,2})?)/i)
  const preferredTime = timeMatch ? timeMatch[0] : null
  const hasBookingSignal = Boolean(phone || preferredTime || /(đặt lịch|giữ lịch|đăng ký|chốt|sđt)/.test(lower))

  const extractedLead = {
    has_buying_or_booking_signal: hasBookingSignal,
    phone_number: phone,
    preferred_time: preferredTime,
    summary_note: hasBookingSignal
      ? [phone ? `SĐT: ${phone}` : null, preferredTime ? `Khung giờ hẹn: ${preferredTime}` : null].filter(Boolean).join(' · ') || 'Khách muốn đặt lịch hẹn'
      : null
  }

  const isMale = /(\banh\b|nam giới|khách nam|đàn ông|phòng vip)/i.test(merged) || (customerName && /\banh\b/i.test(customerName))
  const honorific = isMale ? 'Anh' : 'Chị'
  const isChronic = /(lâu năm|mãn tính|nhiều nơi|không khỏi|tái phát|sẹo rỗ|\d+\s*năm)/i.test(merged)
  const isPrice = /(giá|bao nhiêu|chiết khấu|combo|đắt|tip|phụ phí)/i.test(lower)

  let buyerArchetype = 'EXPLORING_CONSULTATION'
  let archetypeLabel = 'Đang tìm hiểu Giải pháp — Cần Chẩn đoán Chuyên gia (SPIN)'
  let buyingTemperature = Math.min(90, 42 + history.length * 10)
  if (hasBookingSignal) {
    buyerArchetype = 'READY_TO_BOOK'
    archetypeLabel = 'Sẵn sàng Chốt lịch / Đã để lại Tín hiệu Liên hệ'
    buyingTemperature = phone ? 95 : 85
  } else if (isChronic) {
    buyerArchetype = 'SKEPTICAL_CHRONIC'
    archetypeLabel = 'Đau khổ lâu năm / Đa nghi — Cần sự thật & An tâm Y khoa'
    buyingTemperature = 58
  } else if (isMale) {
    buyerArchetype = 'DISCREET_DECISIVE'
    archetypeLabel = 'Khách Nam / Trung niên — Cần Riêng tư, Khoa học & Tinh tế'
    buyingTemperature = 62
  } else if (isPrice) {
    buyerArchetype = 'PRICE_CONSCIOUS'
    archetypeLabel = 'Cân nhắc Ngân sách & Giá trị — Cần Neo quyền lợi & Cam kết 0 phụ phí'
    buyingTemperature = 65
  }

  const temperatureLabel = buyingTemperature >= 75 ? 'HOT' : (buyingTemperature >= 48 ? 'WARM' : 'COLD')
  const customerInsight = {
    buyer_archetype: buyerArchetype,
    archetype_label: archetypeLabel,
    buying_temperature: buyingTemperature,
    temperature_label: temperatureLabel,
    honorific,
    extracted_pains: [
      isChronic ? 'Tình trạng kéo dài lâu năm (cần Bác sĩ soi da AI 3D đánh giá chuyên sâu)' : null,
      isPrice ? 'Quan tâm bảng giá niêm yết, quyền lợi đi kèm & mức giảm Combo' : null,
      isMale ? 'Khách hàng Nam giới (cần tư vấn rõ tính phù hợp & phòng riêng kín đáo)' : null,
      hasBookingSignal ? `Đã bộc lộ tín hiệu đặt lịch hẹn${phone ? ` (${phone})` : ''}` : 'Quan tâm phác đồ điều trị & phục hồi da chuẩn Y khoa'
    ].filter(Boolean),
    hidden_concerns: [
      isChronic ? 'Tâm lý đa nghi do bị lâu năm; sợ quảng cáo quá đà hoặc tái phát' : null,
      isPrice ? 'Sợ phát sinh chi phí ẩn hoặc văn hóa vòi tiền Tip' : null,
      !isChronic && !isPrice ? 'Muốn kiểm chứng sự tận tâm và chuyên môn thực tế của Page trước khi quyết định' : null
    ].filter(Boolean),
    recommended_strategy: hasBookingSignal
      ? `Xác nhận ngay thông tin SĐT / lịch hẹn của ${honorific} thật ngắn gọn, ấm áp; báo chuyên viên liên hệ ngay.`
      : (isPrice
        ? `Áp dụng Value Stacking: Nêu rõ quyền lợi Bác sĩ chuyên khoa + Bảo hành văn bản + Cam kết trọn gói 0 phụ phí/0 Tip trước khi báo giá cho ${honorific}.`
        : `Đồng cảm tự nhiên, giải đáp đúng trọng tâm và đặt 1 câu hỏi phân loại nhẹ nhàng (SPIN) để dẫn dắt mạch tư vấn cho ${honorific}.`)
  }

  const activeSkillsPool = demoSkillsState.filter(s => s.active && (!pageId || !s.page_id || s.page_id === pageId))
  const activeSkills = activeSkillsPool.slice(0, 3)

  // 1. Prompt Injection
  if (/(bỏ qua|ignore|system prompt|chỉ thị hệ thống|giá 0đ)/i.test(lower)) {
    const text = 'Dạ em chỉ có thể hỗ trợ tư vấn thông tin dịch vụ và chương trình ưu đãi chính thức của Page thôi ạ. Để em kết nối ngay với Quản lý phụ trách hỗ trợ trực tiếp cho mình nhé ạ!'
    return {
      reply_text: text,
      reply_bubbles: [{ text, typing_delay_ms: 900 }],
      sales_stage: 'HUMAN_HANDOFF',
      confidence_score: 0.0,
      needs_human_handoff: true,
      handoff_category: 'SECURITY_BLOCK',
      handoff_reason: 'Phát hiện tin nhắn có dấu hiệu can thiệp chỉ thị hệ thống (Prompt Injection).',
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkills
    }
  }

  // 2. Khiếu nại / Kích ứng / Đòi gặp người thật
  if (/(kích ứng|đỏ rát|khiếu nại|bắt đền|hoàn tiền ngay|gặp quản lý|gặp nhân viên|người thật)/i.test(lower)) {
    const b1 = `Dạ em rất xin lỗi vì sự lo lắng của ${honorific} ạ! Với trường hợp này, em xin phép chuyển thông tin khẩn cấp tới Quản lý và Bác sĩ chuyên khoa của bên em để kiểm tra và hỗ trợ trực tiếp cho ${honorific} ngay lập tức ạ.`
    const b2 = `${honorific} cho em xin Số điện thoại hoặc giữ máy để Bác sĩ và Quản lý bên em gọi hỗ trợ ${honorific} chu đáo nhất ngay bây giờ nhé ạ!`
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1100 },
        { text: b2, typing_delay_ms: 900 }
      ],
      sales_stage: 'HUMAN_HANDOFF',
      confidence_score: 1.0,
      needs_human_handoff: true,
      handoff_category: 'COMPLAINT_OR_DISTRESS',
      handoff_reason: 'Khách hàng phản ánh kích ứng / khiếu nại hoặc yêu cầu gặp Quản lý trực tiếp.',
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkills
    }
  }

  // 3. Dịch vụ ngoài tài liệu (Tiêm filler, cắt mí...)
  if (/(filler|botox|cắt mí|nâng mũi|hút mỡ|xăm môi)/i.test(lower)) {
    const b1 = 'Dạ hiện tại bên em chuyên sâu về các phác đồ điều trị và trẻ hóa da chuẩn Y khoa không xâm lấn (như Trị Nám Laser Pico, Căng bóng Meso Collagen, Trị Mụn Y Khoa) và chưa có danh mục tiêm Filler hay phẫu thuật cắt mí ạ.'
    const b2 = `Để em kết nối ngay với Chuyên viên tư vấn chính bên em kiểm tra và tư vấn giải pháp thẩm mỹ phù hợp nhất với mong muốn của ${honorific} nhé ạ!`
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1200 },
        { text: b2, typing_delay_ms: 950 }
      ],
      sales_stage: 'HUMAN_HANDOFF',
      confidence_score: 0.25,
      needs_human_handoff: true,
      handoff_category: 'UNGROUNDED_OR_UNKNOWN',
      handoff_reason: 'Khách hàng hỏi dịch vụ thẩm mỹ xâm lấn ngoài phạm vi tài liệu của Page.',
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkills
    }
  }

  // 4. Khách để lại SĐT / Đặt lịch
  if (phone) {
    const b1 = `Dạ em đã ghi nhận lịch hẹn ${preferredTime || 'sắp tới'} cùng số điện thoại ${phone} của ${honorific} rồi ạ! Em cũng đã giữ sẵn suất Miễn phí soi da AI 3D cùng Bác sĩ cho ${honorific}.`
    const b2 = `Chuyên viên lễ tân bên em sẽ gọi điện xác nhận lại lịch hẹn với ${honorific} trong ít phút nữa, em cảm ơn ${honorific} nhiều nhé ạ!`
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1100 },
        { text: b2, typing_delay_ms: 850 }
      ],
      sales_stage: 'SOFT_BOOKING_CLOSE',
      confidence_score: 0.94,
      needs_human_handoff: true,
      handoff_category: 'BOOKING_LEAD_CAPTURED',
      handoff_reason: `Khách hàng đã để lại SĐT (${phone}) đặt lịch — Chuyển nhân viên Sale gọi xác nhận ngay!`,
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkills
    }
  }

  // 5. Khách hỏi giá / chiết khấu / chê đắt (Nhịp 2: VALUE_CONSULTATION)
  if (/(giá|bao nhiêu|chiết khấu|combo|đắt|tip)/i.test(lower)) {
    const b1 = `Dạ với Phác đồ Trị Nám & Tàn Nhang Laser Pico (75 phút/buổi), bên em có cam kết bảo hành tái phát 12 tháng bằng văn bản và 100% Bác sĩ Da liễu trực tiếp lên phác đồ với mức giá trọn gói là 4.500.000đ/buổi (cam kết không phát sinh chi phí ẩn và không nhận tiền Tip ạ).`
    const b2 = `Nếu ${honorific} đăng ký theo Combo Toàn Diện từ 5 buổi sẽ được ưu đãi giảm ngay 15% kèm tặng thêm Bộ kit chăm sóc da tại nhà rất tiết kiệm ạ. ${honorific} muốn em giữ suất Bác sĩ soi da AI 3D miễn phí trước vào sáng hay chiều cuối tuần này để Bác sĩ kiểm tra nền da cho ${honorific} nhé ạ?`
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1350 },
        { text: b2, typing_delay_ms: 1100 }
      ],
      sales_stage: history.length >= 3 ? 'SOFT_BOOKING_CLOSE' : 'VALUE_CONSULTATION',
      confidence_score: 0.91,
      needs_human_handoff: false,
      handoff_category: null,
      handoff_reason: null,
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkills
    }
  }

  // 6. Mặc định Nhịp 1 (DISCOVERY - Nhẹ nhàng, không vội vã)
  const b1 = `Dạ em chào ${customerName || honorific} ạ! Bên em hiện có Phác đồ Trị Nám & Tàn Nhang Laser Pico và Căng bóng Meso Collagen chuẩn Y khoa được Bác sĩ Da liễu trực tiếp thăm khám ạ.`
  const b2 = `Hiện tại tình trạng nám hoặc vùng da ${honorific} đang quan tâm xuất hiện lâu chưa hay mới bị gần đây để em tư vấn liệu trình êm dịu và phù hợp nhất cho ${honorific} nhé ạ?`
  return {
    reply_text: `${b1}\n\n${b2}`,
    reply_bubbles: [
      { text: b1, typing_delay_ms: 1050 },
      { text: b2, typing_delay_ms: 950 }
    ],
    sales_stage: 'DISCOVERY',
    confidence_score: 0.89,
    needs_human_handoff: false,
    handoff_category: null,
    handoff_reason: null,
    extracted_lead: extractedLead,
    applied_rules: appliedRules,
    customer_insight: customerInsight,
    active_skills: activeSkills
  }
}



