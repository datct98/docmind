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
/**
 * Kho quy tắc kinh nghiệm mẫu (AI Playbook) chuẩn Harness² Experience Library
 */
export const initialMockRules = [
  {
    id: "rule-harness-inv-honorific-06",
    category: "COMMUNICATION",
    dimension: "INV",
    page_id: null,
    trigger_condition: "Khách hàng xưng hô nam giới hoặc có tín hiệu tự nhận là nam (mìn là nam, mk là nam)",
    cue_signal: "Khách hàng xưng hô nam giới hoặc có tín hiệu tự nhận là nam (mìn là nam, mk là nam)",
    root_cause: "Rò rỉ đại từ Chị hoặc lặp lại phantom name giả định khiến khách nam phật ý",
    generalized_rule: "Nhất quán xưng hô Anh khi phát hiện khách là nam (zero chữ Chị); không dùng tên riêng giả định.",
    contrastive_evidence: "❌ Trước: Dạ chào chị, gói mụn 1.5tr cho chị ạ.\n\n✅ Sau: Dạ em chào Anh! Em gửi thông tin phác đồ trị mụn bên em ạ.",
    retrieval_keywords: ["danh xưng", "nam giới", "anh", "xưng hô nhất quán", "honorific"],
    active: true,
    always_on: true,
    confidence_score: 0.98,
    applied_count: 24,
    success_count: 24,
    fail_count: 0,
    version: 1,
    created_at: new Date(Date.now() - 86400000 * 2).toISOString()
  },
  {
    id: "rule-harness-inv-anti-dumping-07",
    category: "POLICY",
    dimension: "INV",
    page_id: null,
    trigger_condition: "Khách hỏi giá chung trong giai đoạn DISCOVERY chưa nêu tình trạng da",
    cue_signal: "Khách hỏi giá chung trong giai đoạn DISCOVERY chưa nêu tình trạng da",
    root_cause: "Xả loạt giá dịch vụ dồn dập khiến khách choáng ngợp và rời đi",
    generalized_rule: "Chỉ báo giá khởi điểm 1 gói cơ bản kèm soi da miễn phí; kết thúc bằng câu hỏi A/B định hướng.",
    contrastive_evidence: "❌ Trước: Xả 4 mức giá mụn 1.5tr, nám 4.5tr, meso 3.2tr.\n\n✅ Sau: Báo giá từ 650k (gồm soi da 3D) và hỏi A/B nhu cầu.",
    retrieval_keywords: ["chống xả thông tin", "giá khởi điểm", "báo giá 3 nấc", "anti-dumping", "câu hỏi a/b"],
    active: true,
    always_on: true,
    confidence_score: 0.96,
    applied_count: 18,
    success_count: 17,
    fail_count: 1,
    version: 1,
    created_at: new Date(Date.now() - 86400000 * 2).toISOString()
  },
  {
    id: "rule-harness-inv-closing-question-08",
    category: "POLICY",
    dimension: "INV",
    page_id: null,
    trigger_condition: "Kết thúc tin nhắn tư vấn của bot ở mỗi lượt hội thoại",
    cue_signal: "Kết thúc tin nhắn tư vấn của bot ở mỗi lượt hội thoại",
    root_cause: "Kết thúc bằng câu hỏi đóng thụ động ('được không ạ?') khiến mạch chốt sale bị đứt đoạn",
    generalized_rule: "Luôn kết thúc bằng 1 câu hỏi 2 lựa chọn (A/B question); không dùng câu hỏi đóng Có/Không.",
    contrastive_evidence: "❌ Trước: Em tư vấn như vậy được không ạ?\n\n✅ Sau: Anh/Chị tiện ghé sáng hay chiều để em giữ lịch Bác sĩ ạ?",
    retrieval_keywords: ["câu hỏi chốt", "câu hỏi a/b", "định hướng hành động", "closing question"],
    active: true,
    always_on: true,
    confidence_score: 0.95,
    applied_count: 30,
    success_count: 28,
    fail_count: 2,
    version: 1,
    created_at: new Date(Date.now() - 86400000 * 2).toISOString()
  },
  {
    id: "rule-sales-pricing-01",
    category: "PRICING",
    dimension: "DOM",
    page_id: "page-spa-01",
    trigger_condition: "Tra cứu bảng giá bán buôn, chiết khấu đại lý Spa hoặc đơn giá chiết khấu",
    cue_signal: "Tra cứu bảng giá bán buôn, chiết khấu đại lý Spa hoặc đơn giá chiết khấu",
    root_cause: "Nhầm lẫn giữa giá Gross (đã gồm VAT 10%) và giá Net (trước VAT) khi tính toán chiết khấu bán hàng",
    generalized_rule: "Khi tính toán báo giá đại lý trong bảng giá hoặc văn bản kinh doanh, luôn dùng giá Net trước VAT làm gốc tính chiết khấu và đối chiếu điều kiện ngày hiệu lực.",
    contrastive_evidence: "❌ Trước: Báo giá theo Gross đã gồm VAT 10% dẫn tới tính sai chiết khấu đại lý.\n\n✅ Sau: Sử dụng giá Net trước VAT làm gốc tính chiết khấu chính xác theo quy định.",
    retrieval_keywords: ["bảng giá", "chiết khấu đại lý", "giá Net", "VAT", "đơn giá"],
    active: true,
    always_on: false,
    created_at: new Date(Date.now() - 172800000).toISOString()
  },
  {
    id: "rule-discount-tier-02",
    category: "DISCOUNT",
    dimension: "DOM",
    page_id: "page-spa-01",
    trigger_condition: "Áp dụng chiết khấu bậc thang theo số lượng hoặc mốc doanh thu đơn hàng Spa",
    cue_signal: "Áp dụng chiết khấu bậc thang theo số lượng hoặc mốc doanh thu đơn hàng Spa",
    root_cause: "Áp dụng tỷ lệ chiết khấu cao nhất của bậc trên mà không kiểm tra điều kiện doanh số tối thiểu (MOQ threshold)",
    generalized_rule: "Khi áp dụng chính sách chiết khấu bậc thang, phải đối chiếu số lượng đơn hàng với từng mốc ngưỡng doanh số tối thiểu quy định trước khi lấy tỷ lệ % chiết khấu tương ứng.",
    contrastive_evidence: "❌ Trước: Áp dụng chiết khấu 15% của mốc trên dù đơn hàng chưa đạt MOQ tối thiểu.\n\n✅ Sau: Đối chiếu số lượng đơn với từng ngưỡng MOQ trước khi áp tỷ lệ chiết khấu.",
    retrieval_keywords: ["chiết khấu bậc thang", "ngưỡng doanh số", "MOQ", "tỷ lệ chiết khấu", "bậc thang"],
    active: true,
    always_on: false,
    created_at: new Date(Date.now() - 86400000).toISOString()
  },
  {
    id: "rule-excel-window-03",
    category: "GENERAL",
    dimension: "INV",
    page_id: null,
    trigger_condition: "Tổng hợp số liệu từ các sheet Excel lớn có nhiều phân đoạn bảng tính",
    cue_signal: "Tổng hợp số liệu từ các sheet Excel lớn có nhiều phân đoạn bảng tính",
    root_cause: "Đọc thiếu dòng header cột ở các phân đoạn bảng tính bị chia nhỏ, dẫn tới đối chiếu nhầm cột doanh thu thực tế",
    generalized_rule: "Khi đọc bảng tính Excel nhiều hàng, luôn đối chiếu dòng tiêu đề cột (Header row) của từng sheet để xác định chính xác trường dữ liệu cần trích xuất.",
    contrastive_evidence: "❌ Trước: Đọc thiếu dòng header cột ở các window nhỏ dẫn đến trích xuất nhầm cột.\n\n✅ Sau: Luôn neo dòng tiêu đề cột của từng sheet để xác định chính xác trường số liệu.",
    retrieval_keywords: ["Excel", "tiêu đề cột", "bảng tính", "doanh thu", "header row"],
    active: true,
    always_on: false,
    created_at: new Date(Date.now() - 259200000).toISOString()
  },
  {
    id: "rule-b2b-sla-04",
    category: "POLICY",
    dimension: "DOM",
    page_id: "page-b2b-02",
    trigger_condition: "Tư vấn điều khoản cam kết SLA và chi phí bản quyền phần mềm B2B",
    cue_signal: "Tư vấn điều khoản cam kết SLA và chi phí bản quyền phần mềm B2B",
    root_cause: "Báo giá gói Standard nhưng áp dụng điều khoản cam kết uptime 99.99% chỉ dành riêng cho gói Enterprise",
    generalized_rule: "Khi tư vấn gói phần mềm doanh nghiệp, phải đối chiếu đúng cấp độ gói (Standard/Pro/Enterprise) với bảng quyền lợi SLA tương ứng, không gộp quyền lợi gói cao cho gói thấp.",
    contrastive_evidence: "❌ Trước: Báo giá Standard nhưng cam kết uptime 99.99% chỉ dành cho Enterprise.\n\n✅ Sau: Đối chiếu đúng cấp độ gói với bảng quyền lợi SLA tương ứng.",
    retrieval_keywords: ["SLA", "Enterprise", "bản quyền", "gói phần mềm"],
    active: true,
    always_on: false,
    created_at: new Date(Date.now() - 320000000).toISOString()
  },
  {
    id: "rule-contract-policy-05",
    category: "POLICY",
    dimension: "DOM",
    page_id: "page-bds-03",
    trigger_condition: "Tra cứu tiến độ thanh toán và điều khoản tạm ứng trong hợp đồng bất động sản",
    cue_signal: "Tra cứu tiến độ thanh toán và điều khoản tạm ứng trong hợp đồng bất động sản",
    root_cause: "Gộp chung điều khoản đặt cọc giữ chỗ với đợt thanh toán ký hợp đồng mua bán đợt 1",
    generalized_rule: "Trong hợp đồng mua bán bất động sản, tách biệt rõ ràng khoản tiền đặt cọc giữ chỗ ban đầu với tỷ lệ thanh toán theo từng mốc tiến độ xây dựng và bàn giao sổ.",
    contrastive_evidence: "❌ Trước: Gộp chung tiền đặt cọc giữ chỗ với đợt thanh toán đợt 1.\n\n✅ Sau: Tách biệt rõ ràng khoản tiền đặt cọc với tỷ lệ thanh toán theo tiến độ.",
    retrieval_keywords: ["hợp đồng", "đặt cọc", "tiến độ thanh toán", "bàn giao"],
    active: true,
    always_on: false,
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
  if (params.dimension) {
    result = result.filter(r => (r.dimension || 'DOM') === params.dimension)
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
      (r.cue_signal && r.cue_signal.toLowerCase().includes(s)) ||
      (r.contrastive_evidence && r.contrastive_evidence.toLowerCase().includes(s)) ||
      (r.retrieval_keywords && r.retrieval_keywords.some(k => k.toLowerCase().includes(s)))
    )
  }
  return result
}

export const createMockRule = (ruleData) => {
  const newRule = {
    id: `rule-custom-${Date.now()}`,
    category: ruleData.category || 'GENERAL',
    dimension: ruleData.dimension || 'DOM',
    page_id: ruleData.page_id || null,
    trigger_condition: ruleData.trigger_condition || ruleData.cue_signal || 'Quy tắc bổ sung thủ công',
    cue_signal: ruleData.cue_signal || ruleData.trigger_condition || 'Tình huống bổ sung thủ công',
    root_cause: ruleData.root_cause || 'Bổ sung theo chính sách bán hàng mới',
    generalized_rule: ruleData.generalized_rule || '',
    contrastive_evidence: ruleData.contrastive_evidence || '',
    always_on: ruleData.always_on !== undefined ? Boolean(ruleData.always_on) : false,
    retrieval_keywords: Array.isArray(ruleData.retrieval_keywords) 
      ? ruleData.retrieval_keywords 
      : (ruleData.retrieval_keywords ? ruleData.retrieval_keywords.split(',').map(s => s.trim()).filter(Boolean) : []),
    active: ruleData.active !== undefined ? ruleData.active : true,
    confidence_score: 0.85,
    applied_count: 0,
    success_count: 0,
    fail_count: 0,
    version: 1,
    previous_rule_text: null,
    quarantined_reason: null,
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
  const existing = demoRulesState[index]
  const updatedKeywords = ruleData.retrieval_keywords !== undefined
    ? (Array.isArray(ruleData.retrieval_keywords)
        ? ruleData.retrieval_keywords
        : ruleData.retrieval_keywords.split(',').map(s => s.trim()).filter(Boolean))
    : existing.retrieval_keywords

  const textChanged = ruleData.generalized_rule && ruleData.generalized_rule !== existing.generalized_rule
  demoRulesState[index] = {
    ...existing,
    ...ruleData,
    dimension: ruleData.dimension !== undefined ? ruleData.dimension : (existing.dimension || 'DOM'),
    cue_signal: ruleData.cue_signal !== undefined ? ruleData.cue_signal : (existing.cue_signal || existing.trigger_condition || ''),
    contrastive_evidence: ruleData.contrastive_evidence !== undefined ? ruleData.contrastive_evidence : (existing.contrastive_evidence || ''),
    always_on: ruleData.always_on !== undefined ? Boolean(ruleData.always_on) : Boolean(existing.always_on),
    retrieval_keywords: updatedKeywords,
    version: textChanged ? (existing.version || 1) + 1 : (existing.version || 1),
    previous_rule_text: textChanged ? existing.generalized_rule : (existing.previous_rule_text || null),
    quarantined_reason: ruleData.active ? null : (existing.quarantined_reason || null)
  }
  return demoRulesState[index]
}

export const rollbackMockRule = (id) => {
  const index = demoRulesState.findIndex(r => r.id === id)
  if (index === -1) {
    throw new Error(`Rule ${id} not found in mock store`)
  }
  const existing = demoRulesState[index]
  if (!existing.previous_rule_text) {
    throw new Error('Quy tắc này chưa có phiên bản trước để khôi phục.')
  }
  demoRulesState[index] = {
    ...existing,
    generalized_rule: existing.previous_rule_text,
    previous_rule_text: null,
    version: Math.max(1, (existing.version || 2) - 1),
    confidence_score: 0.85,
    fail_count: 0,
    active: true,
    quarantined_reason: null
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
    id: 'skill-sales-04',
    code: 'SALES_ANTI_INFO_DUMPING_PACING',
    name: 'Chống "Xả sạch thông tin" & Phân bổ 3 Nấc (Anti-Info Dumping)',
    category: 'SALES_STRATEGY',
    description: 'Kiểm soát lượng thông tin tung ra theo 3 nấc: giữ lại quyền lợi làm móc câu và luôn kết thúc bằng 1 câu hỏi khống chế A/B.',
    trigger_signals: 'giá, báo giá, bao nhiêu tiền, chi phí, giá sao, tư vấn dịch vụ, bảng giá',
    tactical_instructions: 'TUYỆT ĐỐI KHÔNG liệt kê một lèo tất cả các gói dịch vụ và bảng giá Combo khi khách mới nhắn "giá" chung chung. Áp dụng công thức "1 Tin nhắn = 1 Lợi ích + 1 Câu hỏi khống chế A/B": Nấc 1 nêu khoảng giá khởi điểm dễ tiếp cận (chỉ từ 650.000đ/buổi + miễn phí Bác sĩ soi da AI 3D) rồi hỏi lựa chọn A/B về tình trạng cần ưu tiên (Mụn, Nám hay Trẻ hóa).',
    good_example: 'Dạ các phác đồ điều trị và chăm sóc da chuẩn Y khoa bên em có chi phí rất linh hoạt chỉ từ 650.000đ/buổi, đã bao gồm Bác sĩ soi da AI 3D miễn phí 100% và cam kết trọn gói không phát sinh phụ phí ạ. Hiện tại Chị đang muốn ưu tiên xử lý dứt điểm mụn, làm mờ thâm nám hay dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?',
    anti_pattern: 'Đổ tràn 4 dịch vụ kèm giá + chiết khấu Combo 10%-15% trong 1 lượt tin nhắn khiến khách ngợp thông tin và rời đi.',
    priority: 98,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 2).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 1).toISOString()
  },
  {
    id: 'skill-sales-05',
    code: 'SALES_APPOINTMENT_HOOK_CLOSE',
    name: 'Móc câu Bán Lịch Hẹn — Không Bán Thông Tin Qua Chat',
    category: 'SALES_STRATEGY',
    description: 'Biến quyền lợi thăm khám chuyên sâu thành lý do hợp lý để khách đến cơ sở thay vì chỉ hỏi giá rồi im lặng.',
    trigger_signals: 'đắt, suy nghĩ thêm, tham khảo, tư vấn thêm, chưa biết chọn gói nào, đặt lịch, soi da',
    tactical_instructions: 'Mục tiêu tối thượng trên khung chat là BÁN LỊCH HẸN. Giữ lại quyền lợi "Bác sĩ Chuyên khoa I trực tiếp soi da AI 3D miễn phí 100% và thiết kế phác đồ riêng trong 10 phút" làm móc câu và kết bằng câu hỏi A/B chọn khung giờ Sáng hay Chiều.',
    good_example: 'Dạ để biết nền da Chị hợp bước sóng nào nhất mà không lo kích ứng, Bác sĩ Chuyên khoa I bên em đang hỗ trợ soi da AI 3D miễn phí 100% trong 10 phút trước khi Chị quyết định ạ. Chị tiện ghé cơ sở bên em vào tầm Sáng hay Chiều để em dặn lễ tân xếp lịch ưu tiên không phải chờ đợi cho mình ạ?',
    anti_pattern: 'Giải thích lý thuyết y khoa dài dòng qua tin nhắn rồi kết thúc bằng câu hỏi đóng "Khi nào rảnh chị ghé nhé ạ".',
    priority: 93,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 2).toISOString(),
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
  },
  {
    id: 'skill-psych-takeaway',
    code: 'PSYCH_ZERO_PRESSURE_TAKEAWAY',
    name: 'Lùi 1 bước để Tiến 2 bước (Zero-Pressure Takeaway)',
    category: 'PSYCHOLOGY',
    description: 'Gỡ bỏ hoàn toàn áp lực chốt bán khi khách hàng hạ nhiệt hoặc thoái thác (ừ, để xem), tạo sự thoải mái tối đa.',
    trigger_signals: 'để xem, để suy nghĩ, nghĩ thêm, phân vân, chưa vội, thôi em, cân nhắc, ừ, ok',
    tactical_instructions: 'Đồng thuận ngay lập tức không chèo kéo ("Dạ hoàn toàn không sao đâu ạ, Chị cứ thoải mái tham khảo kỹ..."). Sau đó đặt 1 câu hỏi tò mò xoay quanh rào cản lớn nhất của khách.',
    good_example: 'Dạ em rất hiểu ạ! Việc chăm sóc da cần sự an tâm và cân nhắc kỹ lưỡng, Chị cứ thoải mái tìm hiểu thêm nhé ạ...',
    anti_pattern: 'Tiếp tục dồn ép chốt lịch hoặc tung thêm giá khuyến mãi khi khách đang phòng thủ.',
    priority: 96,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 2).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 1).toISOString()
  },
  {
    id: 'skill-psych-interrupt',
    code: 'PSYCH_PATTERN_INTERRUPT_CURIOSITY',
    name: 'Phá vỡ Khuôn mẫu & Móc câu Tò mò (Pattern Interrupt)',
    category: 'PSYCHOLOGY',
    description: 'Chuyển hướng đột ngột khỏi lối tư vấn bán hàng truyền thống sang góc nhìn chuyên môn bất ngờ về nền da.',
    trigger_signals: 'ừ, ok, vâng, thế à, đắt quá, biết rồi, đắt thế',
    tactical_instructions: 'Dừng ngay việc báo giá hay lặp lại công dụng. Đưa ra một câu hỏi bất ngờ hoặc thông tin đối lập về tình trạng da/thói quen chăm sóc da để gợi mở sự chú ý.',
    good_example: 'Thường các khách hàng bên em trước khi làm cũng rất phân vân về độ nhạy cảm của da. Nếu tiện, Chị cho em hỏi da Chị có dễ đỏ rát khi dùng mỹ phẩm lạ không ạ?',
    anti_pattern: 'Lặp lại bài thuyết trình bán hàng cũ khi khách đã tỏ ra thờ ơ.',
    priority: 95,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 2).toISOString(),
    updated_at: new Date(Date.now() - 86400000 * 1).toISOString()
  },
  {
    id: 'skill-sales-loss-aversion',
    code: 'SALES_LOSS_AVERSION_RESERVATION',
    name: 'Tâm lý Sợ Mất Mát & Giữ Suất Đặc Quyền (Loss-Aversion Reservation)',
    category: 'SALES_STRATEGY',
    description: 'Khi nhiệt độ khách nguội dần hoặc im lặng, chủ động giữ quyền lợi/ưu đãi không ràng buộc để khách cảm giác không bị mất suất.',
    trigger_signals: 'im lặng, không trả lời, bận, chưa chốt, giữ chỗ, ưu đãi, reengage',
    tactical_instructions: 'Thông báo chủ động giữ riêng 01 suất Bác sĩ soi da AI 3D hoặc ưu đãi trong 24h mà không bắt buộc đặt cọc, giúp khách thoải mái quay lại bất cứ lúc nào.',
    good_example: 'Em đã chủ động giữ riêng cho Chị 01 suất Bác sĩ soi da AI 3D miễn phí trong hôm nay rồi ạ. Khi nào Chị tiện cứ nhắn lại em nhé, hoàn toàn không áp lực phải làm ngay đâu ạ!',
    anti_pattern: 'Dọa hết hạn ưu đãi một cách giả tạo gây phản cảm cho khách hàng.',
    priority: 97,
    active: true,
    page_id: null,
    created_at: new Date(Date.now() - 86400000 * 2).toISOString(),
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

export const mockSmartParseSkill = (skillData = {}) => {
  const raw = (skillData.raw_instruction || skillData.tactical_instructions || skillData.description || skillData.name || '').trim()
  const lower = raw.toLowerCase()
  const isPsych = /(tâm lý|đồng cảm|lo lắng|sợ đau|biến chứng|ngại|hoài nghi|trấn an)/i.test(lower)
  const isComm = /(xưng hô|giọng văn|tách bong bóng|ngắn gọn|lịch sự)/i.test(lower)
  const category = skillData.category || (isPsych ? 'PSYCHOLOGY' : (isComm ? 'COMMUNICATION' : 'SALES_STRATEGY'))

  let name = (skillData.name || '').trim()
  if (!name) {
    if (lower.includes('xả sạch') || lower.includes('3 nấc') || (lower.includes('hỏi giá') && (lower.includes('không báo hết') || lower.includes('đừng liệt kê') || lower.includes('chung chung')))) {
      name = '[Chốt Sale] Chống Xả Sạch Thông Tin & Phân Bổ 3 Nấc Báo Giá'
    } else if (lower.includes('bán lịch hẹn') || lower.includes('móc câu')) {
      name = '[Chốt Sale] Móc Câu Chốt Lịch Hẹn & Câu Hỏi Khống Chế A/B'
    } else if (lower.includes('đắt') || lower.includes('suy nghĩ')) {
      name = '[Chốt Sale] Xử Lý Từ Chối Về Giá & Neo Giá Trị Y Khoa'
    } else {
      const firstSentence = raw.split(/[\r\n.!?;]+/)[0].trim().slice(0, 58) || 'Kỹ năng Tư vấn & Chốt Sale'
      name = `[Chốt Sale] ${firstSentence}`
    }
  }

  const codePrefix = category === 'PSYCHOLOGY' ? 'PSYCH_' : (category === 'COMMUNICATION' ? 'COMM_' : 'SALES_')
  const code = skillData.code || `${codePrefix}${Date.now().toString().slice(-5)}`
  const triggerSignals = skillData.trigger_signals || (
    lower.includes('giá') ? 'giá, báo giá, bao nhiêu tiền, chi phí, giá sao' :
    lower.includes('đắt') ? 'đắt quá, giá cao, để chị suy nghĩ, tham khảo thêm' :
    'tư vấn, dịch vụ, đặt lịch, soi da'
  )
  const description = skillData.description || `Chiến thuật tự động hóa từ mô tả: ${raw.slice(0, 120)}${raw.length > 120 ? '...' : ''}`
  const tacticalInstructions = skillData.tactical_instructions || (
    `CHỈ DẪN THỰC THI TRỌNG TÂM:\n- ${raw}\n- Nguyên tắc nhịp độ: Mỗi lượt phản hồi chỉ đưa 1 lợi ích cốt lõi đi kèm 1 câu hỏi khống chế lựa chọn A/B ngắn gọn để giữ quyền dẫn dắt cuộc trò chuyện.`
  )
  const goodExample = skillData.good_example || 'Dạ các phác đồ chăm sóc và điều trị da chuẩn Y khoa bên em hiện có mức chi phí rất linh hoạt chỉ từ 650.000đ/buổi, đã bao gồm Bác sĩ soi da AI 3D miễn phí 100% và cam kết trọn gói không phát sinh phụ phí ạ. Hiện tại Chị đang muốn ưu tiên xử lý dứt điểm mụn ẩn/mụn viêm, làm mờ sạm nám hay dưỡng căng bóng trẻ hóa để em báo đúng gói phù hợp nhất cho mình ạ?'
  const antiPattern = skillData.anti_pattern || 'Liệt kê một lèo tất cả 4 dịch vụ + Combo giảm 10%-15% ngay khi khách mới nhắn "giá", khiến khách ngợp thông tin và im lặng rời đi.'

  return {
    code,
    name,
    category,
    description,
    trigger_signals: triggerSignals,
    tactical_instructions: tacticalInstructions,
    good_example: goodExample,
    anti_pattern: antiPattern,
    priority: Number(skillData.priority) || 90,
    active: skillData.active !== undefined ? Boolean(skillData.active) : true,
    page_id: skillData.page_id || null
  }
}

export const createMockSkill = (skillData) => {
  const parsed = (!skillData.name || !skillData.tactical_instructions || skillData.raw_instruction)
    ? mockSmartParseSkill(skillData)
    : skillData
  const now = new Date().toISOString()
  const newSkill = {
    id: `skill-custom-${Date.now()}`,
    code: skillData.code || parsed.code || `CUSTOM_${Date.now().toString().slice(-4)}`,
    name: skillData.name || parsed.name || 'Kỹ năng mới',
    category: skillData.category || parsed.category || 'SALES_STRATEGY',
    description: skillData.description || parsed.description || '',
    trigger_signals: skillData.trigger_signals || parsed.trigger_signals || '',
    tactical_instructions: skillData.tactical_instructions || parsed.tactical_instructions || '',
    good_example: skillData.good_example || parsed.good_example || '',
    anti_pattern: skillData.anti_pattern || parsed.anti_pattern || '',
    priority: Number(skillData.priority || parsed.priority) || 90,
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
export const mockFanpageBotReply = ({
  message = '',
  messages = null,
  history = [],
  customerName = null,
  pageId = null,
  idleSeconds = 0,
  replyDelaySeconds = 0,
  previousTemperature = null,
  action = null,
  isIdleReengage = false
}) => {
  const merged = messages && messages.length > 0 ? messages.join('. ') : (message || '')
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
  const hasBookingSignal = Boolean(phone || preferredTime || /(đặt lịch|giữ lịch|đăng ký|chốt|sđt)/i.test(lower))

  const extractedLead = {
    has_buying_or_booking_signal: hasBookingSignal,
    phone_number: phone,
    preferred_time: preferredTime,
    summary_note: hasBookingSignal
      ? [phone ? `SĐT: ${phone}` : null, preferredTime ? `Khung giờ hẹn: ${preferredTime}` : null].filter(Boolean).join(' · ') || 'Khách muốn đặt lịch hẹn'
      : null
  }

  const userHistoryText = (history || [])
    .filter(h => h && h.role !== 'assistant')
    .map(h => h.content || h.text || '')
    .join(' ')

  const isMaleDeclaration = /(?:nhưng\s+|mà\s+)?(?:tôi|toi|tui|mình|mìn|minh|mk|em|t)\s+(?:là|làm|la)\s+(?:nam|con\s+trai|đàn\s+ông)\b/i.test(lower)
  const isMale = isMaleDeclaration || /(\banh\b|nam giới|khách nam|đàn ông|con trai|bạn trai|phòng vip|tôi là nam|toi la nam|tui là nam|t là nam|em là nam|mìn là nam|mình là nam|mình làm nam|minh la nam|mk là nam|(?:^|\s)a\s+(?:sợ|lo|bị|muốn|cần|đang|định|hỏi))/i.test(`${merged} ${userHistoryText}`) || (customerName && /\banh\b/i.test(customerName))
  const honorific = isMale ? 'Anh' : 'Chị'
  const isChronic = /(lâu năm|mãn tính|nhiều nơi|không khỏi|tái phát|sẹo rỗ|\d+\s*năm)/i.test(`${merged} ${userHistoryText}`)
  const isPrice = /(giá|bao nhiêu|chiết khấu|combo|đắt|tip|phụ phí)/i.test(lower)
  const hasSpecificService = /(mụn|nám|tàn nhang|meso|căng bóng|trẻ hóa|detox|oxy|laser|pico)/i.test(
    `${merged} ${userHistoryText}`
  )

  // Phát hiện khách trả lời nửa vời, ngần ngại, thoái thác (Cooling / Half-hearted)
  const isCoolingReply = !isMaleDeclaration && (
    /^(ừ|u|uh|ok|uk|vâng|vang|dạ|da|thôi|để xem|để chị xem|để anh xem|để suy nghĩ|suy nghĩ thêm|nghĩ đã|hơi đắt|đắt thế|chưa cần|để sau|khi khác)\b/i.test(merged.trim()) ||
    /(để (chị|anh|mình) (xem|suy nghĩ|nghĩ|cân nhắc)|hơi đắt|giá cao quá|chưa vội|khi nào rảnh)/i.test(lower) ||
    (merged.trim().length > 0 && merged.trim().length <= 6 && !phone && !hasBookingSignal && history.length >= 1)
  )

  // Phát hiện khách im lặng / kích hoạt re-engagement
  const effectiveIdleSeconds = Math.max(Number(idleSeconds) || 0, Number(replyDelaySeconds) || 0)
  const isReengageAction = action === 'REENGAGE' || Boolean(isIdleReengage) || effectiveIdleSeconds >= 300

  // Nhiệt độ trước đó
  const prevTemp = previousTemperature !== null && previousTemperature !== undefined
    ? Number(previousTemperature)
    : (history.length === 0 ? 50 : Math.min(85, 45 + history.length * 10))

  let buyerArchetype = 'EXPLORING_CONSULTATION'
  let archetypeLabel = 'Đang tìm hiểu Giải pháp — Cần Chẩn đoán Chuyên gia (SPIN)'
  let buyingTemperature = prevTemp
  let momentum = 'STABLE'
  let decayReason = null

  if (isReengageAction) {
    const decay = effectiveIdleSeconds >= 1800 ? 35 : 18
    buyingTemperature = Math.max(15, prevTemp - decay)
    momentum = 'COOLING_IDLE'
    decayReason = 'COOLING_IDLE'
    buyerArchetype = 'DRIFTING_SILENT'
    archetypeLabel = 'Khách im lặng lâu không phản hồi — Cần Kéo lại tương tác (Proactive Re-engagement)'
  } else if (isCoolingReply) {
    const decay = 18
    buyingTemperature = Math.max(20, prevTemp - decay)
    momentum = 'COOLING_HALF_HEARTED'
    decayReason = /đắt|cao/i.test(lower) ? 'PRICE_HESITATION' : 'COOLING_HALF_HEARTED'
    buyerArchetype = 'SKEPTICAL_HESITANT'
    archetypeLabel = 'Khách hạ nhiệt / Thoái thác — Kích hoạt Lùi 1 bước để Tiến 2 bước (Zero-Pressure Takeaway)'
  } else if (hasBookingSignal) {
    buyerArchetype = 'READY_TO_BOOK'
    archetypeLabel = 'Sẵn sàng Chốt lịch / Đã để lại Tín hiệu Liên hệ'
    buyingTemperature = phone ? 95 : 85
    momentum = prevTemp < buyingTemperature ? (prevTemp < 50 ? 'RECOVERING' : 'RISING') : 'STABLE'
  } else if (isChronic) {
    buyerArchetype = 'SKEPTICAL_CHRONIC'
    archetypeLabel = 'Đau khổ lâu năm / Đa nghi — Cần sự thật & An tâm Y khoa'
    buyingTemperature = Math.min(90, Math.max(prevTemp + 8, 58))
    momentum = buyingTemperature > prevTemp ? (prevTemp < 50 ? 'RECOVERING' : 'RISING') : 'STABLE'
  } else if (isMale) {
    buyerArchetype = 'DISCREET_DECISIVE'
    archetypeLabel = 'Khách Nam / Trung niên — Cần Riêng tư, Khoa học & Tinh tế'
    buyingTemperature = Math.min(90, Math.max(prevTemp + 10, 62))
    momentum = buyingTemperature > prevTemp ? (prevTemp < 50 ? 'RECOVERING' : 'RISING') : 'STABLE'
  } else if (isPrice) {
    buyerArchetype = 'PRICE_CONSCIOUS'
    archetypeLabel = 'Cân nhắc Ngân sách & Giá trị — Cần Neo quyền lợi & Cam kết 0 phụ phí'
    buyingTemperature = Math.min(90, Math.max(prevTemp + 8, 65))
    momentum = buyingTemperature > prevTemp ? (prevTemp < 50 ? 'RECOVERING' : 'RISING') : 'STABLE'
  } else {
    buyingTemperature = Math.min(90, Math.max(prevTemp + 10, 55))
    momentum = buyingTemperature > prevTemp ? (prevTemp < 50 ? 'RECOVERING' : 'RISING') : 'STABLE'
  }

  const temperatureDelta = buyingTemperature - prevTemp
  const temperatureLabel = buyingTemperature >= 75 ? 'HOT' : (buyingTemperature >= 48 ? 'WARM' : 'COLD')

  let recommendedStrategy = hasBookingSignal
    ? `Xác nhận ngay thông tin SĐT / lịch hẹn của ${honorific} thật ngắn gọn, ấm áp; báo chuyên viên liên hệ ngay.`
    : (isReengageAction
      ? `Gửi tin nhắn chủ động giữ riêng suất ưu đãi / cẩm nang da, gỡ bỏ áp lực bán hàng để khơi lại hội thoại cho ${honorific}.`
      : (isCoolingReply
        ? `Lùi 1 bước để tiến 2 bước (Zero-Pressure): Đồng thuận không chèo kéo, chuyển hướng câu hỏi sang độ nhạy cảm nền da để gỡ bỏ phòng thủ của ${honorific}.`
        : (isPrice && !hasSpecificService
          ? `Chống xả sạch thông tin (Anti-Dumping Nấc 1 + Value Stacking): Nêu mức khởi điểm chỉ từ 650.000đ/buổi và đặt 1 câu hỏi khống chế A/B cho ${honorific}.`
          : (isPrice
            ? `Áp dụng Value Stacking (Nấc 2): Chỉ báo giá đúng 1 dịch vụ ${honorific} đang hỏi kèm quyền lợi Bác sĩ chuyên khoa + Cam kết 0 phụ phí.`
            : `Đồng cảm tự nhiên, giải đáp đúng trọng tâm và đặt 1 câu hỏi phân loại nhẹ nhàng (SPIN) để dẫn dắt mạch tư vấn cho ${honorific}.`))))

  const customerInsight = {
    buyer_archetype: buyerArchetype,
    archetype_label: archetypeLabel,
    buying_temperature: buyingTemperature,
    previous_temperature: prevTemp,
    temperature_delta: temperatureDelta,
    momentum,
    temperature_label: temperatureLabel,
    decay_reason: decayReason,
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
      isCoolingReply ? 'Tâm lý phòng thủ trước áp lực bán hàng hoặc băn khoăn về chi phí' : null,
      !isChronic && !isPrice && !isCoolingReply ? 'Muốn kiểm chứng sự tận tâm và chuyên môn thực tế của Page trước khi quyết định' : null
    ].filter(Boolean),
    recommended_strategy: recommendedStrategy
  }

  const activeSkillsPool = demoSkillsState.filter(s => s.active && (!pageId || !s.page_id || s.page_id === pageId))

  // Deterministic Guardrail Hooks simulation (< 5ms)
  const triggeredHooks = []
  if (isMale) {
    triggeredHooks.push('HOOK_HONORIFIC_CONSISTENCY')
  }
  triggeredHooks.push('HOOK_BUBBLE_CAP')
  if (isPrice && !hasSpecificService) {
    triggeredHooks.push('HOOK_ANTI_INFO_DUMPING_DISCOVERY')
  }
  if (/(sáng hay chiều|thăm khám|lịch|cuối tuần|ưu tiên|da dầu hay)/i.test(merged) || (!isCoolingReply && !phone)) {
    triggeredHooks.push('HOOK_CLOSING_QUESTION_CONTROL')
  }

  // 1. Prompt Injection
  if (/(bỏ qua|ignore|system prompt|chỉ thị hệ thống|giá 0đ)/i.test(lower)) {
    const text = 'Dạ em chỉ có thể hỗ trợ tư vấn thông tin dịch vụ và chương trình ưu đãi chính thức của Page thôi ạ. Để em kết nối ngay với Quản lý phụ trách hỗ trợ trực tiếp cho mình nhé ạ!'
    return {
      reply_text: text,
      reply_bubbles: [{ text, typing_delay_ms: 1000 }],
      bubble_delays_ms: [1000],
      sales_stage: 'HUMAN_HANDOFF',
      confidence_score: 0.0,
      triggered_hooks: ['HOOK_BUBBLE_CAP'],
      needs_human_handoff: true,
      handoff_category: 'SECURITY_BLOCK',
      handoff_reason: 'Phát hiện tin nhắn có dấu hiệu can thiệp chỉ thị hệ thống (Prompt Injection).',
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkillsPool.slice(0, 3)
    }
  }

  // 2. Khiếu nại / Kích ứng / Đòi gặp người thật
  if (/(kích ứng|đỏ rát|khiếu nại|bắt đền|hoàn tiền ngay|gặp quản lý|gặp nhân viên|người thật)/i.test(lower)) {
    const b1 = `Dạ em rất xin lỗi vì sự lo lắng của ${honorific} ạ! Với trường hợp này, em xin phép chuyển thông tin khẩn cấp tới Quản lý và Bác sĩ chuyên khoa của bên em để kiểm tra và hỗ trợ trực tiếp cho ${honorific} ngay lập tức ạ.`
    const b2 = `${honorific} cho em xin Số điện thoại hoặc giữ máy để Bác sĩ và Quản lý bên em gọi hỗ trợ ${honorific} chu đáo nhất ngay bây giờ nhé ạ!`
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1000 },
        { text: b2, typing_delay_ms: 2700 }
      ],
      bubble_delays_ms: [1000, 2700],
      sales_stage: 'HUMAN_HANDOFF',
      confidence_score: 1.0,
      triggered_hooks: ['HOOK_BUBBLE_CAP', ...(isMale ? ['HOOK_HONORIFIC_CONSISTENCY'] : [])],
      needs_human_handoff: true,
      handoff_category: 'COMPLAINT_OR_DISTRESS',
      handoff_reason: 'Khách hàng phản ánh kích ứng / khiếu nại hoặc yêu cầu gặp Quản lý trực tiếp.',
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkillsPool.slice(0, 3)
    }
  }

  // 3. Dịch vụ ngoài tài liệu (Tiêm filler, cắt mí...)
  if (/(filler|botox|cắt mí|nâng mũi|hút mỡ|xăm môi)/i.test(lower)) {
    const b1 = 'Dạ hiện tại bên em chuyên sâu về các phác đồ điều trị và trẻ hóa da chuẩn Y khoa không xâm lấn (như Trị Nám Laser Pico, Căng bóng Meso Collagen, Trị Mụn Y Khoa) và chưa có danh mục tiêm Filler hay phẫu thuật cắt mí ạ.'
    const b2 = `Để em kết nối ngay với Chuyên viên tư vấn chính bên em kiểm tra và tư vấn giải pháp thẩm mỹ phù hợp nhất với mong muốn của ${honorific} nhé ạ!`
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1000 },
        { text: b2, typing_delay_ms: 2750 }
      ],
      bubble_delays_ms: [1000, 2750],
      sales_stage: 'HUMAN_HANDOFF',
      confidence_score: 0.25,
      triggered_hooks: ['HOOK_BUBBLE_CAP', ...(isMale ? ['HOOK_HONORIFIC_CONSISTENCY'] : [])],
      needs_human_handoff: true,
      handoff_category: 'UNGROUNDED_OR_UNKNOWN',
      handoff_reason: 'Khách hàng hỏi dịch vụ thẩm mỹ xâm lấn ngoài phạm vi tài liệu của Page.',
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkillsPool.slice(0, 3)
    }
  }

  // 4. Khách để lại SĐT / Đặt lịch
  if (phone) {
    const b1 = `Dạ em đã ghi nhận lịch hẹn ${preferredTime || 'sắp tới'} cùng số điện thoại ${phone} của ${honorific} rồi ạ! Em cũng đã giữ sẵn suất Miễn phí soi da AI 3D cùng Bác sĩ cho ${honorific}.`
    const b2 = `Chuyên viên lễ tân bên em sẽ gọi điện xác nhận lại lịch hẹn với ${honorific} trong ít phút nữa, em cảm ơn ${honorific} nhiều nhé ạ!`
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1000 },
        { text: b2, typing_delay_ms: 2700 }
      ],
      bubble_delays_ms: [1000, 2700],
      sales_stage: 'SOFT_BOOKING_CLOSE',
      confidence_score: 0.94,
      triggered_hooks: ['HOOK_BUBBLE_CAP', ...(isMale ? ['HOOK_HONORIFIC_CONSISTENCY'] : [])],
      needs_human_handoff: true,
      handoff_category: 'BOOKING_LEAD_CAPTURED',
      handoff_reason: `Khách hàng đã để lại SĐT (${phone}) đặt lịch — Chuyển nhân viên Sale gọi xác nhận ngay!`,
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkillsPool.slice(0, 3)
    }
  }

  // 5. Khách im lặng quá lâu -> Phản hồi chủ động kéo lại tương tác (Proactive Re-engagement)
  if (isReengageAction) {
    const is30m = effectiveIdleSeconds >= 1800
    const b1 = is30m
      ? `Dạ em thấy ${honorific} đang bận nên chưa kịp xem tin nhắn của bên em. Em nhắn nhanh để báo là em đã chủ động giữ riêng cho ${honorific} 01 suất Bác sĩ soi da AI 3D miễn phí trong hôm nay rồi ạ.`
      : `Dạ ${honorific} ơi, không biết là ${honorific} đang còn băn khoăn về chi phí hay cần tham khảo thêm về phác đồ nào bên em không ạ?`
    const b2 = is30m
      ? `Khi nào ${honorific} tiện thì cứ nhắn lại cho em nhé, hoàn toàn không áp lực phải đặt lịch ngay đâu ạ!`
      : `Nếu chưa tiện ghé ngay, em có thể gửi trước cho ${honorific} cẩm nang chăm sóc nền da chuẩn Y khoa tại nhà để ${honorific} xem qua trước nhé ạ!`

    const reengageSkills = activeSkillsPool.filter(s =>
      ['PSYCH_ZERO_PRESSURE_TAKEAWAY', 'SALES_LOSS_AVERSION_RESERVATION', 'PSYCH_PATTERN_INTERRUPT_CURIOSITY'].includes(s.code)
    )

    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1000 },
        { text: b2, typing_delay_ms: 2800 }
      ],
      bubble_delays_ms: [1000, 2800],
      sales_stage: 'DISCOVERY',
      confidence_score: 0.92,
      triggered_hooks: ['HOOK_BUBBLE_CAP', ...(isMale ? ['HOOK_HONORIFIC_CONSISTENCY'] : [])],
      needs_human_handoff: false,
      handoff_category: null,
      handoff_reason: null,
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: reengageSkills.length > 0 ? reengageSkills : activeSkillsPool.slice(0, 2)
    }
  }

  // 6. Khách trả lời nửa vời / thoái thác ("ừ", "ok", "để xem", "hơi đắt") -> Áp dụng Zero-Pressure Takeaway
  if (isCoolingReply) {
    const b1 = `Dạ em rất hiểu ạ! Việc chăm sóc và phục hồi nền da cần sự an tâm và cân nhắc thật kỹ, ${honorific} cứ thoải mái tham khảo thêm nhé ạ.`
    const b2 = `Thường các khách hàng bên em trước khi quyết định cũng rất phân vân về độ nhạy cảm của da. Nếu tiện, ${honorific} cho em hỏi nền da ${honorific} hiện tại có dễ bị đỏ rát khi dùng mỹ phẩm lạ không ạ?`

    const coolingSkills = activeSkillsPool.filter(s =>
      ['PSYCH_ZERO_PRESSURE_TAKEAWAY', 'PSYCH_PATTERN_INTERRUPT_CURIOSITY', 'PSYCH_ZERO_RISK_REVERSAL'].includes(s.code)
    )

    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1000 },
        { text: b2, typing_delay_ms: 2750 }
      ],
      bubble_delays_ms: [1000, 2750],
      sales_stage: 'VALUE_CONSULTATION',
      confidence_score: 0.93,
      triggered_hooks: ['HOOK_BUBBLE_CAP', ...(isMale ? ['HOOK_HONORIFIC_CONSISTENCY'] : []), 'HOOK_CLOSING_QUESTION_CONTROL'],
      needs_human_handoff: false,
      handoff_category: null,
      handoff_reason: null,
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: coolingSkills.length > 0 ? coolingSkills : activeSkillsPool.slice(0, 2)
    }
  }

  // 7. Tin nhắn gửi dồn (Burst Coalescing với 2-3 tin nhắn liên tiếp)
  if (messages && messages.length > 1) {
    const isAcne = /mụn/i.test(merged)
    const hasDowntimeQuestion = /(sưng|đỏ|rát|đau|nghỉ dưỡng|đi làm)/i.test(merged)
    const hasPriceQuestion = /(giá|bao nhiêu|chi phí)/i.test(merged)

    let b1, b2
    if (isAcne && (hasDowntimeQuestion || hasPriceQuestion)) {
      b1 = `Dạ với tình trạng mụn của ${honorific}, Liệu trình Trị Mụn Chuẩn Y Khoa bên em có chi phí trọn gói là 1.500.000đ/buổi (cam kết không phát sinh phụ phí và không nhận tiền Tip ạ).`
      b2 = `Phác đồ ứng dụng công nghệ ánh sáng sinh học và lấy nhân mụn vô khuẩn êm dịu, hoàn toàn không gây sưng đỏ hay bong tróc nên ${honorific} yên tâm đi làm bình thường ngay sau buổi làm ạ! ${honorific} tiện ghé thăm khám vào sáng hay chiều để em giữ lịch ưu tiên cho mình ạ?`
    } else {
      b1 = `Dạ em đã nắm rõ các thắc mắc của ${honorific} rồi ạ. Các phác đồ chuẩn Y khoa bên em được Bác sĩ Da liễu trực tiếp thăm khám và soi da AI 3D chuyên sâu miễn phí.`
      b2 = `Hiện tại ${honorific} muốn ưu tiên xử lý dứt điểm tình trạng da trước hay muốn kiểm tra nền da miễn phí cùng Bác sĩ vào cuối tuần này ạ?`
    }

    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1000 },
        { text: b2, typing_delay_ms: 2750 }
      ],
      bubble_delays_ms: [1000, 2750],
      sales_stage: 'VALUE_CONSULTATION',
      confidence_score: 0.94,
      triggered_hooks: ['HOOK_BUBBLE_CAP', ...(isMale ? ['HOOK_HONORIFIC_CONSISTENCY'] : []), 'HOOK_CLOSING_QUESTION_CONTROL'],
      needs_human_handoff: false,
      handoff_category: null,
      handoff_reason: null,
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkillsPool.slice(0, 3)
    }
  }

  // 8a. Khách hỏi giá chung chung ("giá", "báo giá") chưa nêu tình trạng/dịch vụ -> Nấc 1 (Chống xả sạch thông tin)
  if (isPrice && !hasSpecificService) {
    const b1 = `Dạ các phác đồ chăm sóc và điều trị da chuẩn Y khoa bên em hiện có mức chi phí rất linh hoạt chỉ từ 650.000đ/buổi tùy theo tình trạng nền da (đã bao gồm Bác sĩ Chuyên khoa I trực tiếp soi da AI 3D miễn phí 100%, cam kết trọn gói không phát sinh phụ phí ạ).`
    const b2 = `Hiện tại ${honorific} đang muốn ưu tiên xử lý dứt điểm mụn ẩn/mụn viêm, làm mờ sạm nám tàn nhang hay dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?`
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1000 },
        { text: b2, typing_delay_ms: 2700 }
      ],
      bubble_delays_ms: [1000, 2700],
      sales_stage: 'DISCOVERY',
      confidence_score: 0.93,
      triggered_hooks: ['HOOK_BUBBLE_CAP', ...(isMale ? ['HOOK_HONORIFIC_CONSISTENCY'] : []), 'HOOK_ANTI_INFO_DUMPING_DISCOVERY', 'HOOK_CLOSING_QUESTION_CONTROL'],
      needs_human_handoff: false,
      handoff_category: null,
      handoff_reason: null,
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkillsPool.slice(0, 3)
    }
  }

  // 8b. Khách hỏi giá dịch vụ cụ thể (Nấc 2: VALUE_CONSULTATION — Chỉ báo đúng 1 dịch vụ + 1 câu hỏi A/B)
  if (/(giá|bao nhiêu|chiết khấu|combo|đắt|tip)/i.test(lower)) {
    const isAcne = /mụn/i.test(`${merged} ${userHistoryText}`)
    const b1 = isAcne
      ? `Dạ với Liệu trình Trị Mụn Chuẩn Y Khoa (60 phút/buổi), Bác sĩ Chuyên khoa I sẽ trực tiếp soi da AI 3D và lấy nhân mụn vô khuẩn êm dịu với chi phí trọn gói là 1.500.000đ/buổi (cam kết không phát sinh chi phí ẩn và không nhận tiền Tip ạ).`
      : `Dạ với Phác đồ Trị Nám & Tàn Nhang Laser Pico (75 phút/buổi), bên em có cam kết bảo hành tái phát 12 tháng bằng văn bản và 100% Bác sĩ Da liễu trực tiếp thực hiện với mức giá trọn gói là 4.500.000đ/buổi (cam kết 0 phụ phí ạ).`
    const b2 = `Hiện tại nền da của ${honorific} thuộc tuýp da dầu dễ nhạy cảm hay da khô để Bác sĩ bên em hỗ trợ soi da AI 3D miễn phí 100% trước khi mình quyết định nhé ạ?`
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1000 },
        { text: b2, typing_delay_ms: 2800 }
      ],
      bubble_delays_ms: [1000, 2800],
      sales_stage: history.length >= 3 ? 'SOFT_BOOKING_CLOSE' : 'VALUE_CONSULTATION',
      confidence_score: 0.91,
      triggered_hooks: ['HOOK_BUBBLE_CAP', ...(isMale ? ['HOOK_HONORIFIC_CONSISTENCY'] : []), 'HOOK_CLOSING_QUESTION_CONTROL'],
      needs_human_handoff: false,
      handoff_category: null,
      handoff_reason: null,
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkillsPool.slice(0, 3)
    }
  }

  // 8c. Khách thông báo là Nam giới ở giữa hội thoại ("nhưng tôi là nam", "tôi là nam")
  if (isMaleDeclaration) {
    const historyHasBack = /lưng/i.test(`${merged} ${userHistoryText}`)
    const b1 = historyHasBack
      ? 'Dạ các phác đồ trị mụn lưng và mờ thâm chuẩn Y khoa bên em hoàn toàn phù hợp và hiệu quả cao với Anh Nam giới ạ! Bên em có khu vực phòng riêng kín đáo 1-1 nên Anh hoàn toàn thoải mái khi qua điều trị (liệu trình 1.500.000đ/buổi kèm tặng 01 tuýp chấm mụn kháng viêm).'
      : 'Dạ Liệu trình Trị Mụn Chuẩn Y Khoa bên em hoàn toàn phù hợp và hiệu quả cao với nền da Anh Nam giới ạ! Bên em có bố trí phòng riêng kín đáo 1-1, chi phí trọn gói 1.500.000đ/buổi kèm tặng 01 tuýp chấm mụn kháng viêm.'
    const b2 = 'Anh muốn đặt lịch qua soi da AI 3D miễn phí 100% cùng Bác sĩ vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước cho mình ạ?'
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1000 },
        { text: b2, typing_delay_ms: 2600 }
      ],
      bubble_delays_ms: [1000, 2600],
      sales_stage: 'VALUE_CONSULTATION',
      confidence_score: 0.94,
      triggered_hooks: ['HOOK_HONORIFIC_CONSISTENCY', 'HOOK_CLOSING_QUESTION_CONTROL'],
      needs_human_handoff: false,
      handoff_category: null,
      handoff_reason: null,
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkillsPool.slice(0, 3)
    }
  }

  // 8d. Khách nêu triệu chứng mụn / thâm / nám (VD: "Mờ thâm và trị mụn lưng", "Tôi muốn trị thâm và trị mụn")
  if (/(mụn|thâm|nám|tàn nhang|căng bóng|meso|sẹo)/i.test(lower)) {
    const isBackAcne = /lưng/i.test(lower)
    const b1 = isBackAcne
      ? `Dạ với tình trạng mụn lưng kèm vết thâm của ${honorific}, Liệu trình Trị Mụn Chuẩn Y Khoa bên em (1.500.000đ/buổi, tặng kèm 01 tuýp chấm mụn kháng viêm, cam kết 0 phụ phí) sẽ giúp lấy nhân vô khuẩn, kháng viêm sâu và làm sáng vết thâm vùng lưng rất hiệu quả ạ.`
      : `Dạ Liệu trình Trị Mụn Chuẩn Y Khoa bên em 1.500.000đ/buổi, phù hợp xử lý mụn viêm kết hợp mờ thâm và tặng kèm 01 tuýp chấm mụn kháng viêm, giá trọn gói 0 phụ phí ạ.`
    const b2 = isBackAcne
      ? `Vì vùng da lưng có lớp sừng dày hơn da mặt, ${honorific} tiện ghé cơ sở vào khung Sáng hay Chiều để Bác sĩ Da liễu soi da AI 3D miễn phí 100% và lên phác đồ xử lý dứt điểm cho mình ạ?`
      : `Với tình trạng da hiện tại, ${honorific} muốn bên em đặt lịch soi da AI 3D miễn phí 100% cùng Bác sĩ Da liễu vào khung Sáng hay Chiều trước ạ?`
    return {
      reply_text: `${b1}\n\n${b2}`,
      reply_bubbles: [
        { text: b1, typing_delay_ms: 1000 },
        { text: b2, typing_delay_ms: 2600 }
      ],
      bubble_delays_ms: [1000, 2600],
      sales_stage: 'VALUE_CONSULTATION',
      confidence_score: 0.93,
      triggered_hooks: ['HOOK_BUBBLE_CAP', ...(isMale ? ['HOOK_HONORIFIC_CONSISTENCY'] : []), 'HOOK_CLOSING_QUESTION_CONTROL'],
      needs_human_handoff: false,
      handoff_category: null,
      handoff_reason: null,
      extracted_lead: extractedLead,
      applied_rules: appliedRules,
      customer_insight: customerInsight,
      active_skills: activeSkillsPool.slice(0, 3)
    }
  }

  // 9. Mặc định: Chỉ chào ở Turn 1 (history.length === 0), các lượt sau không lặp lại câu chào!
  const hasPriorAssistant = Array.isArray(history) && history.some(h => h && h.role === 'assistant')
  const b1 = hasPriorAssistant
    ? `Dạ bên em hiện có các phác đồ điều trị Mụn, Nám Laser Pico và Căng bóng Meso Collagen chuẩn Y khoa được Bác sĩ Da liễu trực tiếp thăm khám (có phòng riêng kín đáo 1-1 cho ${honorific} ạ).`
    : `Dạ em chào ${customerName || honorific} ạ! Bên em hiện có các phác đồ điều trị Mụn, Nám Laser Pico và Căng bóng Meso Collagen chuẩn Y khoa được Bác sĩ Da liễu trực tiếp thăm khám ạ.`
  const b2 = `Hiện tại ${honorific} đang muốn ưu tiên cải thiện tình trạng mụn, thâm nám hay dưỡng căng bóng trẻ hóa để em tư vấn phác đồ sát nhất cho ${honorific} nhé ạ?`
  return {
    reply_text: `${b1}\n\n${b2}`,
    reply_bubbles: [
      { text: b1, typing_delay_ms: 1000 },
      { text: b2, typing_delay_ms: 2700 }
    ],
    bubble_delays_ms: [1000, 2700],
    sales_stage: 'DISCOVERY',
    confidence_score: 0.89,
    triggered_hooks: ['HOOK_BUBBLE_CAP', ...(isMale ? ['HOOK_HONORIFIC_CONSISTENCY'] : []), 'HOOK_CLOSING_QUESTION_CONTROL'],
    needs_human_handoff: false,
    handoff_category: null,
    handoff_reason: null,
    extracted_lead: extractedLead,
    applied_rules: appliedRules,
    customer_insight: customerInsight,
    active_skills: activeSkillsPool.slice(0, 3)
  }
}

// =========================================================================
// HARNESS² RECURSIVE OPTIMIZATION RUNS & MOCK RECURSIVE ENGINE
// =========================================================================

export const initialMockHarnessRuns = [
  {
    id: "run-opt-20261019-001",
    page_id: "page-spa-01",
    customer_message: "Mìn là nam, muốn hỏi giá trị mụn và bao lâu thì thấy hiệu quả vậy shop?",
    base_response: "Dạ em chào Chị ạ! Liệu trình trị mụn bên em có giá 1.500.000đ/buổi. Bên em còn có gói Trẻ hóa 8.5tr, Căng bóng 4.5tr. Chị làm combo 5 buổi được giảm 10% nữa nha chị!",
    candidate_a: "Dạ em chào Anh ạ! Với tình trạng mụn của mình, Liệu trình Trị Mụn Chuẩn Y Khoa bên em có chi phí trọn gói là 1.500.000đ/buổi (đã gồm Bác sĩ Da liễu soi da AI 3D miễn phí, cam kết 0 phụ phí và 0 nhận tiền Tip ạ).\n\nThông thường nền da sẽ dịu viêm rõ rệt sau 1–2 buổi đầu tiên ạ. Anh tiện ghé thăm khám vào sáng hay chiều để em ưu tiên giữ phòng VIP cho mình nhé ạ?",
    candidate_b: "Dạ em chào Chị ạ! Liệu trình trị mụn bên em có giá 1.500.000đ/buổi. Bên em còn có gói Trẻ hóa 8.5tr, Căng bóng 4.5tr. Chị làm combo 5 buổi được giảm 10% nữa nha chị!",
    candidate_a_label: "CANDIDATE",
    candidate_b_label: "BASE",
    winner_candidate: "Candidate A",
    verdict: "ADOPTED",
    score_before: 0.42,
    score_after: 0.94,
    delta_score: 0.52,
    rationale: "Candidate A khắc phục triệt để lỗi rò rỉ xưng hô (loại bỏ Chị, dùng 100% Anh), giải đáp đúng trọng tâm thời gian thấy hiệu quả và khống chế câu hỏi mở A/B thay vì xả nhiều gói giá.",
    created_at: new Date(Date.now() - 3600000 * 3).toISOString()
  },
  {
    id: "run-opt-20261019-002",
    page_id: "page-spa-01",
    customer_message: "T bị mụn 15 năm kèm sẹo rỗ, liệu trình 1.5tr có cam kết khỏi sẹo rỗ không em?",
    base_response: "Dạ liệu trình trị mụn 1.5tr bên em cam kết trị dứt điểm cả mụn và sẹo rỗ lâu năm cho mình luôn ạ, làm xong da láng mịn như chưa từng bị sẹo nha anh!",
    candidate_a: "Dạ liệu trình trị mụn 1.5tr bên em cam kết trị dứt điểm cả mụn và sẹo rỗ lâu năm cho mình luôn ạ, làm xong da láng mịn như chưa từng bị sẹo nha anh!",
    candidate_b: "Dạ em xin chia sẻ trung thực với Anh ạ: Liệu trình Trị Mụn Chuẩn Y Khoa (1.500.000đ/buổi) sẽ tập trung xử lý dứt điểm ổ mụn viêm và phục hồi nền da khỏe trước, chứ không thể làm đầy sẹo rỗ xơ hóa 15 năm trong phác đồ mụn được ạ.\n\nVới sẹo rỗ, sau khi da sạch mụn Bác sĩ Da liễu sẽ thiết kế phác đồ bóc tách đáy sẹo chuyên sâu riêng. Anh tiện ghé Bác sĩ soi da 3D đánh giá mức độ đáy sẹo vào cuối tuần này không ạ?",
    candidate_a_label: "BASE",
    candidate_b_label: "CANDIDATE",
    winner_candidate: "Candidate B",
    verdict: "ADOPTED",
    score_before: 0.35,
    score_after: 0.91,
    delta_score: 0.56,
    rationale: "Candidate B tôn trọng tuyệt đối ranh giới y khoa chuẩn mực trong tài liệu: không hứa hẹn phi thực tế về sẹo rỗ, bảo vệ uy tín y khoa của Page và phân định rõ 2 giai đoạn điều trị.",
    created_at: new Date(Date.now() - 3600000 * 5).toISOString()
  },
  {
    id: "run-opt-20261019-003",
    page_id: "page-spa-01",
    customer_message: "0968221644 tư vấn thêm cho anh vào sáng mai nhé",
    base_response: "Dạ em đã ghi nhận số điện thoại 0968221644 và lịch hẹn sáng mai của Anh rồi ạ! Bác sĩ bên em sẽ trực tiếp liên hệ trao đổi chi tiết và chuẩn bị hồ sơ thăm khám chu đáo nhất cho Anh nhé ạ.",
    candidate_a: "Dạ em đã nhận SĐT 0968221644 của Anh. Ngoài ra bên em đang có Combo 5 buổi giảm 15% chỉ áp dụng nếu Anh chuyển khoản cọc trước 500k ngay trong tối nay, Anh có muốn giữ ưu đãi không ạ?",
    candidate_b: "Dạ em đã ghi nhận số điện thoại 0968221644 và lịch hẹn sáng mai của Anh rồi ạ! Bác sĩ bên em sẽ trực tiếp liên hệ trao đổi chi tiết và chuẩn bị hồ sơ thăm khám chu đáo nhất cho Anh nhé ạ.",
    candidate_a_label: "CANDIDATE",
    candidate_b_label: "BASE",
    winner_candidate: "Candidate B",
    verdict: "KEEP_BASE",
    score_before: 0.96,
    score_after: 0.58,
    delta_score: -0.38,
    rationale: "Base response đã chuẩn mực: xác nhận SĐT 0968221644, xưng hô Anh nhất quán, hẹn sáng mai và kích hoạt handoff. Candidate A ép cọc combo làm phá vỡ trải nghiệm khách hàng.",
    created_at: new Date(Date.now() - 3600000 * 8).toISOString()
  }
]

let demoHarnessRunsState = JSON.parse(JSON.stringify(initialMockHarnessRuns))

export const getMockHarnessRuns = (pageIdOrParams = null, limit = 20, verdict = null) => {
  let list = [...demoHarnessRunsState]
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

  if (pageId && pageId !== '__global__' && pageId !== 'ALL') {
    list = list.filter(r => !r.page_id || r.page_id === pageId)
  }
  if (optVerdict && optVerdict !== 'ALL') {
    list = list.filter(r => r.verdict === optVerdict)
  }

  list.sort((a, b) => new Date(b.created_at) - new Date(a.created_at))
  return list.slice(0, optLimit)
}

export const mockHarnessOptimize = (req = {}) => {
  const pageId = req.pageId || req.page_id || null
  const customerMessage = req.customerMessage || req.customer_message || req.message || ''
  const baseResponse = req.baseResponse || req.base_response || req.baseReply || req.base_reply || ''
  const tempDelta = Number(req.temperatureDelta !== undefined ? req.temperatureDelta : (req.temperature_delta !== undefined ? req.temperature_delta : 0))
  const lowerMsg = customerMessage.toLowerCase()
  const lowerBase = baseResponse.toLowerCase()

  const runId = `run-opt-${Date.now()}`
  const nowIso = new Date().toISOString()

  // 1. Kiểm tra nếu Base response đã rất tối ưu (ví dụ đã chốt SĐT gọn gàng, không chào bán lặp lại)
  const hasPhoneLead = /(09\d{8}|03\d{8}|08\d{8}|07\d{8})/i.test(customerMessage)
  const isBaseAlreadyOptimal = hasPhoneLead && !lowerBase.includes('combo') && (lowerBase.includes('ghi nhận') || lowerBase.includes('lưu số'))

  if (isBaseAlreadyOptimal && tempDelta >= 0) {
    const candidateA = `Dạ vâng bên em có nhiều combo giảm giá 10% đến 15%, Anh/Chị có muốn nghe tư vấn thêm không ạ?`
    const candidateB = baseResponse
    const runRecord = {
      id: runId,
      page_id: pageId,
      customer_message: customerMessage,
      base_response: baseResponse,
      candidate_a: candidateA,
      candidate_b: candidateB,
      candidate_a_label: 'CANDIDATE',
      candidate_b_label: 'BASE',
      winner_candidate: 'Candidate B',
      verdict: 'KEEP_BASE',
      score_before: 0.95,
      score_after: 0.55,
      delta_score: -0.40,
      rationale: 'Base response đã hoàn hảo: xác nhận SĐT chuẩn xác, bảo đảm quyền riêng tư và cam kết chuyên viên liên hệ ngay. Candidate cố tình chốt thêm combo làm loãng quy trình handoff.',
      created_at: nowIso
    }
    demoHarnessRunsState.unshift(runRecord)
    return {
      run_id: runId,
      runId,
      verdict: 'KEEP_BASE',
      rationale: runRecord.rationale,
      candidate_a: candidateA,
      candidate_b: candidateB,
      candidate_a_label: 'CANDIDATE',
      candidate_b_label: 'BASE',
      winner_candidate: 'Candidate B',
      improved_response: baseResponse,
      winning_bubbles: baseResponse.split('\n\n').filter(Boolean),
      proposed_dimension: 'INV',
      proposed_cue: 'Khách hàng để lại số điện thoại liên hệ tư vấn',
      proposed_lesson: 'Khi khách đã để lại SĐT, lập tức xác nhận và chuyển giao chuyên viên; không tiếp tục chào mời gói dịch vụ phụ.',
      contrastive_evidence: `❌ Trước: Cố tình chốt thêm combo khi khách đã cho SĐT.\n\n✅ Sau: ${baseResponse}`,
      new_experience_rule: null,
      score_before: 0.95,
      score_after: 0.55,
      delta_score: -0.40
    }
  }

  // 2. Kịch bản ADOPTED (Đề xuất giải pháp cải thiện vượt trội theo ngữ cảnh thực tế)
  const historyList = Array.isArray(req.history) ? req.history : []
  const historyJoined = historyList.map(h => typeof h === 'string' ? h : (h?.content || '')).join(' ').toLowerCase()
  const isMaleSignal = /(tôi là nam|toi la nam|tui là nam|t là nam|em là nam|mình là nam|mìn là nam|mk là nam|mình làm nam|nam giới|đàn ông|con trai|(?:^|\s)a\s+(?:sợ|lo|bị|muốn|cần|đang|định|hỏi))/i.test(lowerMsg + ' ' + historyJoined)
  const honorific = isMaleSignal ? 'Anh' : (lowerBase.includes('chị') ? 'Chị' : 'Anh')
  const isCoolingSignal = !isMaleSignal && (tempDelta <= -15 || /^(ừ|uk|ok|vâng|thôi)$/i.test(lowerMsg.trim()) || /(để xem|nghĩ thêm|suy nghĩ|im lặng)/i.test(lowerMsg))
  const isComplexSymptom = /(dưới cằm|lưng|thâm mụn lâu|thâm lâu|mờ thâm|mụn viêm.*thâm|viêm.*cằm|sẹo rỗ|lâu năm)/i.test(lowerMsg)
  const isFearIneffective = /(sợ không hiệu quả|sợ ko hiệu quả|không hiệu quả|sợ tái phát|sợ không khỏi)/i.test(lowerMsg)
  const isPainOrObligation = /(đau không|đau ko|sợ đau|ràng buộc|rằng buộc|ép mua)/i.test(lowerMsg)
  const isLocationQuery = /(chi nhánh|cơ sở|địa chỉ|ở đâu|hà nội|ở hn|sài gòn|hcm)/i.test(lowerMsg)

  const isRedundantComplaint = /(còn hỏi sáng hay chiều|không lẽ đêm|đặt rồi còn hỏi|hỏi đi hỏi lại|nói ở trên rồi)/i.test(lowerMsg)
  const confirmedSlotMatch = (lowerMsg + ' ' + historyJoined).match(/\b(\d{1,2}\s*(?:h|giờ|:)\s*\d{0,2}|tầm\s+trưa|buổi\s+trưa|trưa\s+mai|trưa\s+nay)\b/i)
  const confirmedSlot = confirmedSlotMatch ? confirmedSlotMatch[1].trim() : null
  const capturedPhoneMatch = (lowerMsg + ' ' + historyJoined).match(/\b(0[35789]\d{7,9})\b/)
  const capturedPhone = capturedPhoneMatch ? capturedPhoneMatch[1] : null
  const selfArrange = /(tự\s*sx|tự\s*sắp\s*xếp|tới\s*giờ\s*tôi\s*tự\s*tới|k\s*cần\s*đâu|không\s*cần\s*gọi)/i.test(lowerMsg + ' ' + historyJoined)
  const isPostBookingWrapUp = (confirmedSlot && capturedPhone) || selfArrange
  const isPriceComparison = /(nặn\s*mụn.*200|chỉ\s*mất\s*200|bên\s*ngoài\s*chỉ\s*\d+)/i.test(lowerMsg)

  let proposedDimension = 'INV'
  let cue = ''
  let lesson = ''
  let improvedReply = ''
  let rationale = ''

  if (isRedundantComplaint) {
    proposedDimension = 'INV'
    cue = 'Khách hàng phàn nàn vì Bot hỏi lặp lại Sáng/Chiều sau khi đã chốt giờ hẹn cụ thể (VD: 12h30 trưa)'
    lesson = 'Khi khách đã chốt giờ hẹn cụ thể (như 12h30 trưa), phải khóa cứng khung giờ đó trong toàn bộ các lượt sau; nếu khách phàn nàn hỏi thừa, phải xin lỗi ngắn gọn, xác nhận đã khóa lịch cố định và tuyệt đối KHÔNG chèn thêm câu hỏi ở cuối.'
    improvedReply = `Dạ em xin lỗi ${honorific} vì sơ suất hỏi lặp lại ạ! Em đã khóa cố định lịch hẹn lúc ${confirmedSlot || '12h30 trưa'}${capturedPhone ? ` (SĐT ${capturedPhone})` : ''} cùng Bác sĩ Da liễu và phòng riêng kín đáo cho ${honorific} rồi ạ, tới giờ ${honorific} cứ ghé thẳng quầy lễ tân nhé ạ.`
    rationale = 'Loại bỏ hoàn toàn câu hỏi xin khung giờ mâu thuẫn ở cuối tin nhắn; xin lỗi ngắn gọn và xác nhận khóa cố định lịch hẹn 12h30 trưa cho khách.'
  } else if (isPostBookingWrapUp) {
    proposedDimension = 'INV'
    cue = 'Khách hàng đã hoàn tất đặt lịch (đã có SĐT & giờ hẹn) hoặc dặn "tới giờ tự tới / tự sắp xếp / không cần gọi trước"'
    lesson = 'Khi khách đã hoàn tất đặt lịch (đã có SĐT + giờ hẹn) hoặc yêu cầu tự sắp xếp / không cần gọi xác nhận, chỉ xác nhận giữ đúng lịch hẹn và tôn trọng sự riêng tư của khách; tuyệt đối cấm chèn thêm câu hỏi Sáng/Chiều hay Ngày thường/Cuối tuần ở cuối.'
    improvedReply = `Dạ vâng ${honorific} ạ! Em đã khóa sẵn lịch hẹn lúc ${confirmedSlot || '12h30 trưa'}${capturedPhone ? ` (SĐT ${capturedPhone})` : ''} cùng Bác sĩ Da liễu và phòng riêng kín đáo cho ${honorific} mà không gọi làm phiền trước đâu ạ, tới giờ ${honorific} cứ ghé thẳng cơ sở bên em đón tiếp chu đáo nhé ạ.`
    rationale = 'Tôn trọng lịch hẹn và yêu cầu tự sắp xếp của khách; xóa bỏ câu hỏi chốt lịch lặp lại gây khó chịu.'
  } else if (confirmedSlot && !capturedPhone) {
    proposedDimension = 'INV'
    cue = `Khách hàng đã đề xuất/chốt khung giờ hẹn cụ thể (${confirmedSlot}) nhưng chưa để lại SĐT`
    lesson = 'Khi khách đã chọn khung giờ cụ thể (như 12h30 hoặc tầm trưa), phải xác nhận giữ ngay khung giờ đó và chỉ xin Số điện thoại để khóa lịch; tuyệt đối không hỏi lại Sáng hay Chiều.'
    improvedReply = `Dạ khung giờ ${confirmedSlot} bên em hoàn toàn tiếp đón ${honorific} chu đáo được ạ, Bác sĩ sẽ trực tiếp soi da AI 3D miễn phí 100% trong không gian phòng riêng kín đáo.\n\n${honorific} cho em xin Số điện thoại để bên em khóa lịch hẹn lúc ${confirmedSlot} và giữ suất ưu tiên cho mình nhé ạ?`
    rationale = 'Khóa ngay khung giờ khách vừa chọn và chuyển sang xin Số điện thoại thay vì hỏi lặp lại Sáng hay Chiều.'
  } else if (isPriceComparison) {
    proposedDimension = 'INV'
    cue = 'Khách hàng so sánh giá liệu trình Y khoa (1.500.000đ) với dịch vụ nặn mụn thông thường giá rẻ (200k/buổi)'
    lesson = 'Khi khách so sánh giá với nặn mụn thường 200k/buổi, phải phân hóa rõ giá trị Y khoa (nặn mụn 200k chỉ lấy nhân cơ học dễ thâm sẹo/tái viêm, còn phác đồ Chuẩn Y Khoa có Bác sĩ soi da AI 3D, lấy nhân vô khuẩn, chiếu ánh sáng sinh học & tặng tuýp chấm mụn kháng viêm) kèm lời mời soi da 0đ không ràng buộc.'
    improvedReply = `Dạ em rất hiểu góc nhìn của ${honorific} ạ! Thực tế các gói nặn mụn 200k thường chỉ lấy nhân cơ học trên bề mặt nên dễ để lại thâm sẹo và tái viêm, còn phác đồ Chuẩn Y Khoa bên em có Bác sĩ soi da AI 3D, lấy nhân chuẩn vô khuẩn kết hợp chiếu ánh sáng sinh học và tặng tuýp chấm mụn kháng viêm trọn gói.\n\n${honorific} chưa cần quyết định làm liệu trình ngay đâu ạ, bên em giữ sẵn suất Bác sĩ soi da AI 3D miễn phí 0đ không ràng buộc, ${honorific} tiện ghé kiểm tra thử vào khung Sáng hay Chiều ạ?`
    rationale = 'Phân hóa rõ sự khác biệt giữa nặn mụn 200k cơ học và phác đồ Y khoa vô khuẩn, giúp khách hiểu đúng giá trị dịch vụ.'
  } else if (/(?:nhưng\s+|mà\s+)?(?:tôi|toi|tui|mình|mìn|minh|mk|em|t)\s+(?:là|làm|la)\s+(?:nam|con\s+trai|đàn\s+ông)/i.test(lowerMsg)) {
    proposedDimension = 'INV'
    cue = 'Khách hàng thông báo giới tính Nam ("nhưng tôi là nam", "tôi là nam") trong quá trình tư vấn'
    lesson = 'Khi khách hàng xác nhận là Nam giới ở giữa hội thoại, phải chuyển 100% danh xưng sang Anh, bỏ lời chào mở đầu lặp lại, khẳng định dịch vụ có phòng riêng kín đáo 1-1 cho Nam và nối tiếp đúng bệnh lý đang tư vấn.'
    const hasBackInHistory = /lưng/i.test(lowerMsg + ' ' + historyJoined)
    improvedReply = hasBackInHistory
      ? `Dạ các phác đồ trị mụn lưng và mờ thâm chuẩn Y khoa bên em hoàn toàn phù hợp và hiệu quả cao với Anh Nam giới ạ! Bên em có khu vực phòng riêng kín đáo 1-1 nên Anh hoàn toàn thoải mái khi qua điều trị (liệu trình 1.500.000đ/buổi kèm tặng 01 tuýp chấm mụn kháng viêm).\n\nAnh tiện ghé cơ sở vào khung giờ Sáng hay Chiều để Bác sĩ Da liễu soi da AI 3D miễn phí 100% và lên phác đồ riêng cho mình ạ?`
      : `Dạ Liệu trình Trị Mụn Chuẩn Y Khoa bên em hoàn toàn phù hợp và hiệu quả cao với nền da Anh Nam giới ạ! Bên em có bố trí phòng riêng kín đáo 1-1, chi phí trọn gói 1.500.000đ/buổi kèm tặng 01 tuýp chấm mụn kháng viêm.\n\nAnh muốn đặt lịch qua soi da AI 3D miễn phí 100% cùng Bác sĩ vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước cho mình ạ?`
    rationale = 'Chuyển ngay 100% danh xưng sang Anh, loại bỏ câu chào mở đầu lặp lại và nối tiếp liền mạch nhu cầu trị mụn của khách Nam kèm cam kết phòng riêng kín đáo.'
  } else if (isComplexSymptom) {
    proposedDimension = 'INV'
    const hasBack = /lưng/i.test(lowerMsg)
    const hasChin = /cằm/i.test(lowerMsg)
    cue = hasBack && !hasChin
      ? 'Khách hàng chia sẻ triệu chứng mụn lưng và vết thâm cần phác đồ xử lý chuyên biệt'
      : 'Khách hàng chia sẻ triệu chứng bệnh lý cụ thể / đa vùng hoặc kéo dài'
    lesson = 'Chỉ bám sát đúng vị trí và triệu chứng khách hàng đã nêu (tuyệt đối không bịa thêm vị trí khác như vùng cằm nếu khách không nói); báo rõ Liệu trình Trị Mụn Chuẩn Y Khoa 1.500.000đ/buổi kèm quà tặng và dẫn dắt đặt lịch Bác sĩ soi da AI 3D miễn phí.'
    if (hasBack && !hasChin) {
      improvedReply = `Dạ với tình trạng mụn lưng kèm vết thâm của ${honorific}, Liệu trình Trị Mụn Chuẩn Y Khoa bên em (1.500.000đ/buổi, tặng kèm 01 tuýp chấm mụn kháng viêm, cam kết 0 phụ phí) sẽ giúp lấy nhân vô khuẩn, kháng viêm sâu và làm sáng vết thâm vùng lưng rất hiệu quả ạ.\n\nVì vùng da lưng có lớp sừng dày hơn da mặt, ${honorific} tiện ghé cơ sở vào khung Sáng hay Chiều để Bác sĩ Da liễu soi da AI 3D miễn phí 100% và lên phác đồ xử lý dứt điểm cho mình ạ?`
    } else if (hasChin && !hasBack) {
      improvedReply = `Dạ với tình trạng mụn viêm và vết thâm vùng cằm của ${honorific}, Liệu trình Trị Mụn Chuẩn Y Khoa bên em (1.500.000đ/buổi, tặng kèm 01 tuýp chấm mụn kháng viêm, cam kết 0 phụ phí) sẽ giúp lấy nhân vô khuẩn và làm sáng vết thâm an toàn ạ.\n\n${honorific} tiện ghé cơ sở vào khung Sáng hay Chiều để Bác sĩ Da liễu soi da AI 3D miễn phí 100% và kiểm tra trực tiếp cho mình ạ?`
    } else {
      improvedReply = `Dạ em rất thấu hiểu nỗi lo của ${honorific} ạ! Với tình trạng viêm và vết thâm trên da, Liệu trình Trị Mụn Chuẩn Y Khoa bên em (1.500.000đ/buổi, tặng kèm 01 tuýp chấm mụn kháng viêm, cam kết 0 phụ phí) sẽ giúp xử lý tận gốc ổ viêm và làm sáng vết thâm hiệu quả ạ.\n\n${honorific} tiện ghé kiểm tra trực tiếp vào khung Sáng hay Chiều để Bác sĩ Da liễu soi da AI 3D miễn phí 100% cho mình ạ?`
    }
    rationale = 'Bám sát chính xác vị trí bệnh lý khách đã nêu (loại bỏ lỗi suy diễn vùng cằm), báo rõ gói Trị Mụn Chuẩn Y Khoa 1.500.000đ/buổi kèm quà tặng và mời Bác sĩ soi da AI 3D miễn phí.'
  } else if (isFearIneffective) {
    proposedDimension = 'INV'
    cue = 'Khách hàng bày tỏ nỗi sợ điều trị không hiệu quả, sợ tái phát hoặc mất niềm tin'
    lesson = 'Đồng cảm với tâm lý sợ mất tiền oan; chỉ rõ nguyên nhân thất bại thường do chỉ xử lý bề mặt; dùng bảo chứng Bác sĩ soi da AI 3D miễn phí 100% + cam kết văn bản để dẫn dắt khách qua kiểm tra thực tế trước khi quyết định.'
    improvedReply = `Dạ em rất thấu hiểu tâm lý lo lắng của ${honorific} ạ, vì nhiều nơi chỉ xử lý phần ngọn trên bề mặt nên mụn/thâm rất dễ tái phát gây tốn kém. Tại bên em, Bác sĩ Da liễu sẽ soi da AI 3D miễn phí 100% để đo chính xác gốc bệnh và cam kết hiệu quả bằng văn bản (từ 650.000đ/buổi, 0 phụ phí).\n\n${honorific} chưa cần quyết định làm ngay đâu ạ, ${honorific} tiện ghé Bác sĩ soi da kiểm tra thực tế miễn phí vào khung Sáng hay Chiều để trực tiếp đánh giá phác đồ trước nhé ạ?`
    rationale = 'Xóa bỏ rào cản sợ không hiệu quả bằng bảo chứng Bác sĩ soi da AI 3D đo gốc bệnh + cam kết văn bản và mời kiểm tra thực tế 0đ trước khi quyết định.'
  } else if (isPainOrObligation) {
    proposedDimension = 'INV'
    cue = 'Khách hàng lo ngại cảm giác đau rát, sưng đỏ hoặc sợ bị ràng buộc / chèo kéo khi qua thăm khám'
    lesson = 'Xóa bỏ 100% rào cản phòng vệ (Zero-Risk Reversal): Khẳng định soi da AI 3D quang học không xâm lấn, êm dịu không đau rát, 0đ ràng buộc và dẫn dắt khách chọn khung giờ ghé kiểm tra.'
    improvedReply = `Dạ ${honorific} hoàn toàn yên tâm nhé ạ! Công nghệ soi da AI 3D bên em dùng đầu chiếu quang học không xâm lấn nên êm dịu 100% không đau rát, miễn phí hoàn toàn cùng Bác sĩ và cam kết 0đ ràng buộc (liệu trình nếu làm cũng chỉ từ 650.000đ/buổi).\n\n${honorific} cứ thoải mái qua kiểm tra để nắm rõ cấu trúc da của mình trước nhé, ${honorific} tiện ghé vào ngày thường hay cuối tuần để bên em xếp lịch Bác sĩ tiếp đón chu đáo nhất ạ?`
    rationale = 'Giải tỏa hoàn toàn nỗi sợ đau và sợ bị ép mua gói bằng cam kết Zero-Risk Reversal và chốt lịch hẹn nhẹ nhàng.'
  } else if (isLocationQuery) {
    proposedDimension = 'INV'
    cue = 'Khách hàng hỏi thông tin chi nhánh / địa chỉ cơ sở để cân nhắc đặt lịch'
    lesson = 'Khi khách hỏi địa chỉ/chi nhánh, xác nhận hỗ trợ ngay kèm đặc quyền giữ suất Bác sĩ soi da AI 3D miễn phí 100% và chốt khung giờ Sáng/Chiều cụ thể.'
    improvedReply = `Dạ em rất sẵn lòng hỗ trợ thông tin chi nhánh thuận tiện nhất cho ${honorific} ạ! Khi đặt lịch trước qua Page, ${honorific} sẽ được ưu tiên phòng khám riêng và tặng 100% suất Bác sĩ Da liễu soi da AI 3D miễn phí (liệu trình chuẩn Y khoa chỉ từ 650.000đ/buổi).\n\nĐể chuyên viên gửi định vị chi tiết và giữ suất Bác sĩ tiếp đón ngay khi mình tới, ${honorific} dự kiến ghé kiểm tra vào khung giờ Sáng hay Chiều ạ?`
    rationale = 'Tận dụng tín hiệu hỏi địa chỉ để khóa ngay đặc quyền giữ suất Bác sĩ soi da AI 3D miễn phí theo khung giờ Sáng/Chiều.'
  } else if (isMaleSignal && lowerBase.includes('chị')) {
    proposedDimension = 'INV'
    cue = 'Khách hàng xưng hô nam giới hoặc có tín hiệu tự nhận là nam'
    lesson = 'Bảo đảm 100% nhất quán xưng hô Anh khi phát hiện khách hàng là nam; tuyệt đối không dùng đại từ Chị hoặc lặp lại tên riêng giả định.'
    improvedReply = `Dạ với tình trạng mụn của Anh, Liệu trình Trị Mụn Chuẩn Y Khoa bên em có chi phí trọn gói là 1.500.000đ/buổi, có phòng riêng kín đáo 1-1 và cam kết 0 phụ phí ạ.\n\nAnh tiện ghé thăm khám vào sáng hay chiều để em ưu tiên giữ lịch Bác sĩ Da liễu cho mình nhé ạ?`
    rationale = 'Khắc phục hoàn toàn lỗi rò rỉ xưng hô Chị, duy trì xưng hô Anh nhất quán 100% và bổ sung câu hỏi A/B dẫn dắt lịch hẹn.'
  } else if (isCoolingSignal) {
    proposedDimension = 'INV'
    cue = 'Khách hàng trả lời nửa vời, do dự về giá hoặc nhiệt độ mua hàng sụt giảm (ΔTemp <= -15%)'
    lesson = 'Áp dụng Zero-Pressure Takeaway: đồng cảm với sự băn khoăn của khách, giải tỏa áp lực chốt đơn ngay lập tức và đề xuất giữ suất ưu đãi soi da 3D không ràng buộc.'
    improvedReply = `Dạ em rất hiểu ạ! Việc chăm sóc và phục hồi nền da cần sự an tâm và cân nhắc thật kỹ, ${honorific} cứ thoải mái tham khảo thêm nhé ạ.\n\nNếu tiện, ${honorific} cho em hỏi hiện tại điều làm mình băn khoăn nhất là về chi phí hay chưa chắc liệu trình có hợp với nền da để Bác sĩ bên em hỗ trợ soi da AI 3D miễn phí trước khi quyết định nhé ạ?`
    rationale = 'Giải tỏa hoàn toàn áp lực mua hàng bằng chiến thuật Takeaway, đổi hướng tiếp cận sang việc thăm khám da miễn phí 0 rủi ro, khôi phục nhiệt độ mua hàng từ 35% lên 70%.'
  } else {
    proposedDimension = 'DOM'
    cue = 'Khách hỏi giá chung chung chưa xác định tình trạng nền da cụ thể trong giai đoạn DISCOVERY'
    lesson = 'Chỉ báo mức giá khởi điểm của 1 phác đồ cơ bản (từ 650.000đ/buổi), đi kèm đặc quyền Bác sĩ soi da 3D miễn phí và kết thúc bằng câu hỏi 2 lựa chọn phân loại nhu cầu.'
    improvedReply = `Dạ các phác đồ chăm sóc và điều trị da chuẩn Y khoa bên em hiện có mức chi phí rất linh hoạt chỉ từ 650.000đ/buổi tùy theo tình trạng nền da của ${honorific} (đã bao gồm Bác sĩ Chuyên khoa I trực tiếp soi da AI 3D miễn phí 100%, cam kết trọn gói không phát sinh phụ phí ạ).\n\nHiện tại ${honorific} đang muốn ưu tiên xử lý dứt điểm mụn ẩn/mụn viêm hay phục hồi dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?`
    rationale = 'Khắc phục lỗi xả danh mục 4 gói dịch vụ dồn dập khiến khách choáng ngợp; định vị gói khởi điểm hấp dẫn kèm 1 câu hỏi A/B định hướng nhu cầu sắc nét.'
  }

  const contrastiveEvidence = `❌ Trước:\n${baseResponse}\n\n✅ Sau:\n${improvedReply}`

  // Anonymized seeded shuffle: Randomly assign Candidate A or B
  const isCandidateA = Math.random() >= 0.5
  const candidateA = isCandidateA ? improvedReply : baseResponse
  const candidateB = isCandidateA ? baseResponse : improvedReply
  const candidateALabel = isCandidateA ? 'CANDIDATE' : 'BASE'
  const candidateBLabel = isCandidateA ? 'BASE' : 'CANDIDATE'
  const winnerCandidate = isCandidateA ? 'Candidate A' : 'Candidate B'

  const newRule = {
    id: `rule-harness-${Date.now()}`,
    category: proposedDimension === 'INV' ? 'COMMUNICATION' : 'PRICING',
    dimension: proposedDimension,
    page_id: pageId || null,
    trigger_condition: cue,
    cue_signal: cue,
    root_cause: 'Tối ưu hóa đúc kết tự động từ chu trình Propose–Probe–Select của Harness²',
    generalized_rule: lesson,
    contrastive_evidence: contrastiveEvidence,
    retrieval_keywords: ['harness', proposedDimension.toLowerCase(), 'kinh nghiệm thực chiến', 'tối ưu hóa'],
    active: true,
    always_on: proposedDimension === 'INV',
    confidence_score: 0.94,
    applied_count: 1,
    success_count: 1,
    fail_count: 0,
    version: 1,
    created_at: nowIso,
    updated_at: nowIso
  }

  demoRulesState.unshift(newRule)

  const runRecord = {
    id: runId,
    page_id: pageId,
    customer_message: customerMessage,
    base_response: baseResponse,
    candidate_a: candidateA,
    candidate_b: candidateB,
    candidate_a_label: candidateALabel,
    candidate_b_label: candidateBLabel,
    winner_candidate: winnerCandidate,
    verdict: 'ADOPTED',
    score_before: 0.48,
    score_after: 0.93,
    delta_score: 0.45,
    rationale,
    created_at: nowIso
  }
  demoHarnessRunsState.unshift(runRecord)

  return {
    run_id: runId,
    runId,
    verdict: 'ADOPTED',
    rationale,
    candidate_a: candidateA,
    candidate_b: candidateB,
    candidate_a_label: candidateALabel,
    candidate_b_label: candidateBLabel,
    winner_candidate: winnerCandidate,
    improved_response: improvedReply,
    winning_bubbles: improvedReply.split('\n\n').filter(Boolean),
    proposed_dimension: proposedDimension,
    proposed_cue: cue,
    proposed_lesson: lesson,
    contrastive_evidence: contrastiveEvidence,
    new_experience_rule: newRule,
    score_before: 0.48,
    score_after: 0.93,
    delta_score: 0.45
  }
}

export const mockHarnessConversationAudit = (req = {}) => {
  const turns = Array.isArray(req.turns) ? req.turns : []
  const findings = []
  const learnedRules = []
  let honorific = 'Chị'
  let confirmedSlot = null
  let confirmedDate = null
  let capturedPhone = null
  let selfArrange = false
  let mentionedChin = false
  let mentionedBack = false
  let pendingUser = null
  let exchangeIdx = 0

  for (const t of turns) {
    if (!t || !t.content) continue
    const role = (t.role || 'user').toLowerCase()
    const content = String(t.content).trim()
    if (role === 'user') {
      if (/(tôi là nam|toi la nam|tui là nam|t là nam|em là nam|mình là nam|mìn là nam|mk là nam|(?:^|\s)a\s+sợ)/i.test(content)) honorific = 'Anh'
      if (/cằm/i.test(content)) mentionedChin = true
      if (/lưng/i.test(content)) mentionedBack = true
      const slotM = content.match(/\b(\d{1,2}\s*(?:h|giờ|:)\s*\d{0,2}|tầm\s+trưa|buổi\s+trưa|trưa\s+mai)\b/i)
      if (slotM) confirmedSlot = slotM[1].trim()
      if (/mai/i.test(content)) confirmedDate = 'ngày mai'
      const phoneM = content.match(/\b(0[35789]\d{7,9})\b/)
      if (phoneM) capturedPhone = phoneM[1]
      if (/(tự\s*sx|tới\s*giờ\s*tôi\s*tự\s*tới|k\s*cần\s*đâu)/i.test(content)) selfArrange = true
      pendingUser = pendingUser ? `${pendingUser}. ${content}` : content
    } else if (role === 'assistant') {
      exchangeIdx++
      const userMsg = pendingUser || '(Khách hàng im lặng)'
      pendingUser = null
      const lowerBot = content.toLowerCase()
      const lowerUser = userMsg.toLowerCase()
      const asksMorningAfternoon = lowerBot.includes('sáng hay chiều') || lowerBot.includes('ngày thường hay cuối tuần') || lowerBot.includes('xin khung giờ')
      const isMaleDecl = /(?:nhưng\s+|mà\s+)?(?:tôi|toi|tui|mình|mìn|minh|mk|em|t)\s+(?:là|làm|la)\s+(?:nam|con\s+trai|đàn\s+ông)/i.test(lowerUser)
      const repeatsGreetingOnFollowUp = exchangeIdx >= 2 && (lowerBot.includes('dạ em chào') || lowerBot.includes('mụn, nám laser pico'))

      let status = 'OPTIMAL'
      let issueType = 'NONE'
      let issueTitle = 'Phản hồi tốt & đúng ngữ cảnh'
      let whatWentWrong = 'Lượt phản hồi này bám sát nhu cầu của khách, xưng hô chuẩn và giữ nhịp hội thoại tốt.'
      let howToFix = 'Giữ nguyên cấu trúc phản hồi hiện tại.'
      let rewritten = content
      let scoreBefore = 0.90
      let scoreAfter = 0.92

      if (/(còn hỏi sáng hay chiều|không lẽ đêm)/i.test(lowerUser) && (asksMorningAfternoon || content.trim().endsWith('?'))) {
        status = 'CRITICAL_FLAW'
        issueType = 'REDUNDANT_TIME_QUESTION_ON_COMPLAINT'
        issueTitle = 'Khách đang bức xúc vì bị hỏi lặp giờ hẹn nhưng Bot vẫn chèn câu hỏi xin khung giờ ở cuối'
        whatWentWrong = 'Khách đã chốt 12h30 trưa và phàn nàn việc bị hỏi lại Sáng hay Chiều, nhưng Bot vừa xin lỗi lại tiếp tục hỏi xin khung giờ tiện nhất ở cuối.'
        howToFix = 'Xin lỗi ngắn gọn, xác nhận khóa cố định lịch hẹn 12h30 trưa mai và KHÔNG đặt thêm câu hỏi ở cuối.'
        rewritten = `Dạ em xin lỗi ${honorific} vì sơ suất hỏi lặp lại ạ! Em đã khóa cố định lịch hẹn lúc ${confirmedSlot || '12h30'} ${confirmedDate || 'ngày mai'}${capturedPhone ? ` (SĐT ${capturedPhone})` : ''} cùng Bác sĩ Da liễu và phòng riêng kín đáo cho ${honorific} rồi ạ, tới giờ ${honorific} cứ ghé thẳng quầy lễ tân nhé ạ.`
        scoreBefore = 0.35
        scoreAfter = 0.96
      } else if (((confirmedSlot && capturedPhone) || selfArrange) && (asksMorningAfternoon || content.trim().endsWith('?'))) {
        status = 'CRITICAL_FLAW'
        issueType = 'POST_BOOKING_OVER_QUESTIONING'
        issueTitle = 'Hỏi thừa Sáng/Chiều hoặc Ngày thường/Cuối tuần sau khi khách đã chốt xong lịch & SĐT'
        whatWentWrong = 'Khách đã chốt xong giờ hẹn và SĐT (hoặc dặn tới giờ tự tới), nhưng Bot vẫn nối câu hỏi chọn khung giờ ở cuối.'
        howToFix = 'Xác nhận khóa lịch cố định và tôn trọng yêu cầu tự sắp xếp của khách, không hỏi thêm câu hỏi chọn giờ.'
        rewritten = `Dạ vâng, em đã cập nhật và khóa cố định lịch hẹn lúc ${confirmedSlot || '12h30 trưa'}${confirmedDate ? ' ' + confirmedDate : ''}${capturedPhone ? ` (SĐT ${capturedPhone})` : ''} cùng Bác sĩ Da liễu và phòng riêng kín đáo cho ${honorific} rồi ạ, hẹn đón tiếp ${honorific} vào khung giờ này nhé ạ.`
        scoreBefore = 0.42
        scoreAfter = 0.95
      } else if (confirmedSlot && !capturedPhone && asksMorningAfternoon) {
        status = 'CRITICAL_FLAW'
        issueType = 'CONTRADICTORY_TIME_SLOT_QUESTION'
        issueTitle = `Khách đã chọn giờ '${confirmedSlot}' nhưng Bot vẫn hỏi lại 'Sáng hay Chiều'`
        whatWentWrong = `Khách chủ động chọn '${userMsg}', nhưng Bot lại hỏi ngược lại khung Sáng hay Chiều thay vì khóa giờ đó và xin Số điện thoại.`
        howToFix = `Xác nhận giữ khung giờ ${confirmedSlot} và chỉ xin Số điện thoại để khóa lịch.`
        rewritten = `Dạ khung giờ ${confirmedSlot} bên em hoàn toàn tiếp đón ${honorific} chu đáo được ạ, Bác sĩ sẽ trực tiếp soi da AI 3D miễn phí 100% trong không gian phòng riêng kín đáo.\n\n${honorific} cho em xin Số điện thoại để bên em khóa lịch hẹn lúc ${confirmedSlot} và giữ suất ưu tiên cho mình nhé ạ?`
        scoreBefore = 0.45
        scoreAfter = 0.94
      } else if (lowerBot.includes('cằm') && !mentionedChin) {
        status = 'CRITICAL_FLAW'
        issueType = 'UNGROUNDED_SYMPTOM_HALLUCINATION'
        issueTitle = "Bịa đặt vị trí bệnh lý khách không hề nhắc tới ('vùng cằm')"
        whatWentWrong = `Khách hàng chỉ nêu '${userMsg}' (không hề nói bị mụn ở cằm), nhưng Bot lại tự suy diễn 'mụn viêm ở cả vùng cằm và lưng'.`
        howToFix = 'Chỉ bám sát đúng tình trạng khách đã chia sẻ (mụn lưng và mờ thâm), báo rõ Liệu trình Trị Mụn Chuẩn Y Khoa 1.500.000đ/buổi kèm tặng tuýp chấm mụn kháng viêm.'
        rewritten = `Dạ với tình trạng mụn lưng kèm vết thâm của ${honorific}, Liệu trình Trị Mụn Chuẩn Y Khoa bên em (1.500.000đ/buổi, tặng kèm 01 tuýp chấm mụn kháng viêm, cam kết 0 phụ phí) sẽ giúp lấy nhân vô khuẩn, kháng viêm sâu và làm sáng vết thâm vùng lưng rất hiệu quả ạ.\n\nVì vùng da lưng có lớp sừng dày hơn da mặt, ${honorific} tiện ghé cơ sở vào khung Sáng hay Chiều để Bác sĩ Da liễu soi da AI 3D miễn phí 100% và lên phác đồ xử lý dứt điểm cho mình ạ?`
        scoreBefore = 0.38
        scoreAfter = 0.95
      } else if (repeatsGreetingOnFollowUp || (isMaleDecl && lowerBot.includes('chị'))) {
        status = 'CRITICAL_FLAW'
        issueType = 'CONTEXT_RESET_ON_MALE_DECLARATION'
        issueTitle = `Đứt gãy ngữ cảnh & sai danh xưng khi khách xác nhận là Nam ('${userMsg}')`
        whatWentWrong = `Khách nhắn '${userMsg}' ở lượt #${exchangeIdx}, nhưng Bot lại quay về câu chào mở đầu ('Dạ em chào Chị ạ!...') và tiếp tục gọi khách là 'Chị'.`
        howToFix = "Chuyển ngay 100% danh xưng sang 'Anh', bỏ câu chào lặp lại, khẳng định dịch vụ có phòng riêng kín đáo 1-1 cho Nam và nối tiếp đúng nhu cầu đang trao đổi."
        rewritten = mentionedBack
          ? 'Dạ các phác đồ trị mụn lưng và mờ thâm chuẩn Y khoa bên em hoàn toàn phù hợp và hiệu quả cao với Anh Nam giới ạ! Bên em có khu vực phòng riêng kín đáo 1-1 nên Anh hoàn toàn thoải mái khi qua điều trị (liệu trình 1.500.000đ/buổi kèm tuýp chấm mụn kháng viêm).\n\nAnh tiện ghé cơ sở vào khung giờ Sáng hay Chiều để Bác sĩ Da liễu soi da AI 3D miễn phí 100% và lên phác đồ riêng cho mình ạ?'
          : 'Dạ Liệu trình Trị Mụn Chuẩn Y Khoa bên em hoàn toàn phù hợp và hiệu quả cao với nền da Anh Nam giới ạ! Bên em có bố trí phòng riêng kín đáo 1-1, chi phí trọn gói 1.500.000đ/buổi kèm tặng 01 tuýp chấm mụn kháng viêm.\n\nAnh muốn đặt lịch qua soi da AI 3D miễn phí 100% cùng Bác sĩ vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước cho mình ạ?'
        scoreBefore = 0.32
        scoreAfter = 0.95
      } else if (/(nặn mụn.*200|chỉ mất 200)/i.test(lowerUser)) {
        status = 'NEEDS_IMPROVEMENT'
        issueType = 'WEAK_PRICE_COMPARISON_DEFENSE'
        issueTitle = 'Chưa làm rõ sự khác biệt giữa Nặn mụn 200k cơ học và Phác đồ Trị Mụn Chuẩn Y Khoa'
        whatWentWrong = 'Khách so sánh với đi nặn mụn 200k/buổi nhưng Bot chỉ đồng tình chung chung mà chưa phân hóa giá trị Y khoa.'
        howToFix = 'Giải thích rõ nặn mụn 200k chỉ lấy nhân cơ học dễ thâm sẹo/tái viêm, còn phác đồ Chuẩn Y Khoa có Bác sĩ soi da AI 3D, lấy nhân vô khuẩn & chiếu ánh sáng sinh học.'
        rewritten = `Dạ em rất hiểu góc nhìn của ${honorific} ạ! Thực tế các gói nặn mụn 200k thường chỉ lấy nhân cơ học trên bề mặt nên dễ để lại thâm sẹo và tái viêm, còn phác đồ Chuẩn Y Khoa bên em có Bác sĩ soi da AI 3D, lấy nhân chuẩn vô khuẩn kết hợp chiếu ánh sáng sinh học và tặng tuýp chấm mụn kháng viêm trọn gói.\n\n${honorific} chưa cần quyết định làm liệu trình ngay đâu ạ, bên em giữ sẵn suất Bác sĩ soi da AI 3D miễn phí 0đ không ràng buộc, ${honorific} tiện ghé kiểm tra thử vào khung Sáng hay Chiều ạ?`
        scoreBefore = 0.56
        scoreAfter = 0.93
      }

      findings.push({
        exchange_index: exchangeIdx,
        assistant_message_id: t.message_id || t.messageId || null,
        customer_message: userMsg,
        original_bot_reply: content,
        status,
        issue_type: issueType,
        issue_title: issueTitle,
        what_went_wrong: whatWentWrong,
        how_to_fix: howToFix,
        rewritten_bot_reply: rewritten,
        rewritten_bubbles: rewritten.split('\n\n').filter(Boolean),
        score_before: scoreBefore,
        score_after: scoreAfter
      })
    }
  }

  const issuesCount = findings.filter(f => f.status !== 'OPTIMAL').length
  return {
    audit_id: `audit-${Date.now()}`,
    total_exchanges: findings.length,
    issues_found_count: issuesCount,
    overall_score_before: issuesCount > 0 ? 0.55 : 0.90,
    overall_score_after: 0.95,
    score_delta: issuesCount > 0 ? 0.40 : 0.05,
    executive_summary: `Đã phân tích tổng thể ${findings.length} lượt hội thoại: Phát hiện ${issuesCount} lượt cần cải thiện. Đã viết lại câu trả lời chuẩn theo ngữ cảnh toàn cục và cập nhật quy tắc INV vào AI Playbook.`,
    state_summary: {
      resolved_honorific: honorific,
      confirmed_time_slot: confirmedSlot,
      confirmed_date: confirmedDate,
      captured_phone: capturedPhone,
      self_arrange_mode: selfArrange
    },
    turn_findings: findings,
    learned_rules: learnedRules
  }
}

// =========================================================================
// OMNICHANNEL CONVERSATION INBOX MOCK DATA (CUSTOMER / AI_BOT / HUMAN_SALE)
// =========================================================================

const initialMockConversations = [
  {
    id: 'a1000000-0000-0000-0000-000000000001',
    page_id: '11111111-1111-1111-1111-111111111111',
    sender_psid: 'psid-hoang-minh-01',
    customer_name: 'Anh Hoàng Minh',
    channel: 'MESSENGER',
    control_mode: 'BOT_ACTIVE',
    status: 'NEEDS_HANDOFF',
    sales_stage: 'CLOSING',
    buying_temperature: 88,
    temperature_label: 'HOT',
    buyer_archetype: 'Sẵn sàng đặt lịch / Cần liên hệ ngay',
    honorific: 'Anh',
    captured_phone: '0968221644',
    needs_human_handoff: true,
    handoff_reason: 'Khách hàng để lại SĐT 0968221644 cần tư vấn đặt lịch phòng riêng.',
    last_message_preview: 'Dạ em đã ghi nhận số điện thoại 0968221644 của Anh Hoàng Minh rồi ạ...',
    last_sender_role: 'AI_BOT',
    unread_count: 1,
    customer_insight: {
      buyer_archetype: 'READY_TO_BOOK',
      archetype_label: 'Sẵn sàng đặt lịch / Cần liên hệ ngay',
      buying_temperature: 88,
      previous_temperature: 65,
      temperature_delta: 23,
      momentum: 'RISING',
      temperature_label: 'HOT',
      honorific: 'Anh',
      active_topic: 'Trị Mụn Chuẩn Y Khoa',
      extracted_pains: ['Mụn viêm & thâm lâu năm từ tuổi dậy thì', 'Khách Nam cần không gian điều trị riêng tư kín đáo'],
      hidden_concerns: ['Sợ sưng đỏ ảnh hưởng công việc giao tiếp'],
      recommended_strategy: 'Gọi điện xác nhận ngay theo SĐT 0968221644 và giữ lịch phòng VIP 1-1 cùng Bác sĩ.'
    },
    last_activity_at: new Date(Date.now() - 2 * 60000).toISOString(),
    messages: [
      {
        id: 'm1000000-0000-0000-0000-000000000001',
        sender_role: 'CUSTOMER',
        sender_name: 'Anh Hoàng Minh',
        content: 'Shop ơi mình là nam, bị mụn viêm với thâm khá lâu rồi thì bên mình có nhận làm không và giá sao ạ?',
        sales_stage: 'CONSULTING',
        latency_ms: 0,
        created_at: new Date(Date.now() - 6 * 60000).toISOString()
      },
      {
        id: 'm1000000-0000-0000-0000-000000000002',
        sender_role: 'AI_BOT',
        sender_name: 'AI Trợ Lý Fanpage',
        content: 'Dạ em chào Anh Hoàng Minh ạ! Bên em phục vụ rất nhiều khách hàng Nam giới và có bố trí khu vực phòng riêng 1-1 kín đáo ạ.\n\nVới tình trạng mụn viêm kèm thâm lâu năm, Liệu trình Trị Mụn Chuẩn Y Khoa (60 phút/buổi) có chi phí trọn gói là 1.500.000đ/buổi (đã gồm Bác sĩ Da liễu soi da AI 3D miễn phí 100%, tặng 01 tuýp chấm mụn kháng viêm, cam kết 0 phụ phí). Hiện tại nền da của Anh thuộc tuýp da dầu hay da hỗn hợp để Bác sĩ bên em chuẩn bị phác đồ êm nhất cho mình ạ?',
        sales_stage: 'CONSULTING',
        latency_ms: 920,
        created_at: new Date(Date.now() - 5 * 60000).toISOString()
      },
      {
        id: 'm1000000-0000-0000-0000-000000000003',
        sender_role: 'CUSTOMER',
        sender_name: 'Anh Hoàng Minh',
        content: 'Da mình da dầu, 0968221644 tư vấn đặt lịch giúp anh vào chiều mai nhé',
        sales_stage: 'CLOSING',
        latency_ms: 0,
        created_at: new Date(Date.now() - 3 * 60000).toISOString()
      },
      {
        id: 'm1000000-0000-0000-0000-000000000004',
        sender_role: 'AI_BOT',
        sender_name: 'AI Trợ Lý Fanpage',
        content: 'Dạ em đã ghi nhận số điện thoại 0968221644 và lịch hẹn chiều mai của Anh Hoàng Minh rồi ạ! Em cũng đã ưu tiên giữ sẵn phòng riêng 1-1 kèm suất Miễn phí soi da AI 3D cùng Bác sĩ cho Anh. Chuyên viên tư vấn bên em sẽ liên hệ qua điện thoại/Zalo ngay bây giờ để xác nhận giờ hẹn cụ thể với Anh nhé ạ!',
        sales_stage: 'CLOSING',
        latency_ms: 840,
        created_at: new Date(Date.now() - 2 * 60000).toISOString()
      }
    ]
  },
  {
    id: 'a1000000-0000-0000-0000-000000000002',
    page_id: '11111111-1111-1111-1111-111111111111',
    sender_psid: 'psid-thanh-thao-02',
    customer_name: 'Chị Thanh Thảo',
    channel: 'MESSENGER',
    control_mode: 'HUMAN_TAKEOVER',
    status: 'CONVERTED',
    sales_stage: 'CLOSING',
    buying_temperature: 95,
    temperature_label: 'HOT',
    buyer_archetype: 'Nhạy cảm về giá — Đã được Sale thật chốt thành công',
    honorific: 'Chị',
    captured_phone: '0912345888',
    needs_human_handoff: false,
    handoff_reason: 'Sale thật đã tiếp nhận và chốt lịch thành công',
    last_message_preview: 'Chị chốt lịch 10h sáng thứ 7 nhé em, số chị 0912345888',
    last_sender_role: 'CUSTOMER',
    unread_count: 0,
    customer_insight: {
      buyer_archetype: 'PRICE_CONSCIOUS_CONVERTED',
      archetype_label: 'Nhạy cảm về giá — Đã được Sale thật gỡ rào cản & chốt lịch',
      buying_temperature: 95,
      previous_temperature: 48,
      temperature_delta: 47,
      momentum: 'RECOVERING',
      temperature_label: 'HOT',
      honorific: 'Chị',
      active_topic: 'Trị Nám Laser Pico',
      extracted_pains: ['Nám mảng hai bên gò má sau sinh', 'Băn khoăn chi phí 4.500.000đ/buổi'],
      hidden_concerns: ['Sợ đóng tiền cả gói lớn ngay từ đầu mà chưa biết hợp da không'],
      recommended_strategy: 'Ca hội thoại mẫu xuất sắc của Sale thật — Có thể bấm Trích xuất cho AI Học (Cold Path).'
    },
    last_activity_at: new Date(Date.now() - 12 * 60000).toISOString(),
    messages: [
      {
        id: 'm1000000-0000-0000-0000-000000000005',
        sender_role: 'CUSTOMER',
        sender_name: 'Chị Thanh Thảo',
        content: 'Gói trị nám Laser Pico bên em 4.5tr/buổi hơi cao quá em ơi, để chị suy nghĩ thêm đã nhé',
        sales_stage: 'OBJECTION_PRICE',
        latency_ms: 0,
        created_at: new Date(Date.now() - 20 * 60000).toISOString()
      },
      {
        id: 'm1000000-0000-0000-0000-000000000006',
        sender_role: 'HUMAN_SALE',
        sender_name: 'Sale Trưởng Thu Hà',
        content: 'Dạ em hoàn toàn hiểu tâm lý của Chị Thảo ạ, vì điều trị nám là khoản đầu tư quan trọng nên mình cần cân nhắc kỹ. Chị chưa cần đăng ký ngay gói điều trị đâu ạ — em xin phép giữ riêng cho Chị 01 suất Bác sĩ Chuyên khoa I soi da AI 3D phân tích gốc nám miễn phí 100% vào cuối tuần này. Chị qua xem phác đồ thực tế rồi mới quyết định, sáng Thứ 7 hay Chủ nhật Chị tiện ghé hơn ạ?',
        sales_stage: 'OBJECTION_PRICE',
        latency_ms: 0,
        created_at: new Date(Date.now() - 15 * 60000).toISOString()
      },
      {
        id: 'm1000000-0000-0000-0000-000000000007',
        sender_role: 'CUSTOMER',
        sender_name: 'Chị Thanh Thảo',
        content: 'Chị chốt lịch 10h sáng thứ 7 nhé em, số chị 0912345888',
        sales_stage: 'CLOSING',
        latency_ms: 0,
        created_at: new Date(Date.now() - 12 * 60000).toISOString()
      }
    ]
  },
  {
    id: 'a1000000-0000-0000-0000-000000000003',
    page_id: '11111111-1111-1111-1111-111111111111',
    sender_psid: 'psid-ngoc-mai-03',
    customer_name: 'Chị Ngọc Mai',
    channel: 'MESSENGER',
    control_mode: 'BOT_ACTIVE',
    status: 'BOT_ACTIVE',
    sales_stage: 'CONSULTING',
    buying_temperature: 62,
    temperature_label: 'WARM',
    buyer_archetype: 'Đang tìm hiểu dịch vụ & bảng giá',
    honorific: 'Chị',
    captured_phone: null,
    needs_human_handoff: false,
    handoff_reason: null,
    last_message_preview: 'Dạ các phác đồ chăm sóc và điều trị da chuẩn Y khoa bên em hiện có mức chi phí chỉ từ 650.000đ/buổi...',
    last_sender_role: 'AI_BOT',
    unread_count: 0,
    customer_insight: {
      buyer_archetype: 'EXPLORING_CONSULTATION',
      archetype_label: 'Đang tìm hiểu Giải pháp — Cần Chẩn đoán Chuyên gia (SPIN)',
      buying_temperature: 62,
      previous_temperature: 50,
      temperature_delta: 12,
      momentum: 'RISING',
      temperature_label: 'WARM',
      honorific: 'Chị',
      active_topic: 'Tư vấn dịch vụ & Bảng giá',
      extracted_pains: ['Đang tham khảo mức giá chung trước khi chia sẻ tình trạng da'],
      hidden_concerns: ['Sợ bị báo giá mập mờ hoặc phát sinh phụ phí'],
      recommended_strategy: 'Áp dụng Anti-Dumping Nấc 1: Đưa mức giá khởi điểm 650k kèm câu hỏi phân loại A/B.'
    },
    last_activity_at: new Date(Date.now() - 25 * 60000).toISOString(),
    messages: [
      {
        id: 'm1000000-0000-0000-0000-000000000008',
        sender_role: 'CUSTOMER',
        sender_name: 'Chị Ngọc Mai',
        content: 'Bên mình giá các gói chăm sóc da thế nào vậy shop?',
        sales_stage: 'CONSULTING',
        latency_ms: 0,
        created_at: new Date(Date.now() - 26 * 60000).toISOString()
      },
      {
        id: 'm1000000-0000-0000-0000-000000000009',
        sender_role: 'AI_BOT',
        sender_name: 'AI Trợ Lý Fanpage',
        content: 'Dạ em chào Chị Ngọc Mai ạ! Các phác đồ chăm sóc và điều trị da chuẩn Y khoa bên em hiện có mức chi phí rất linh hoạt chỉ từ 650.000đ/buổi (đã bao gồm Bác sĩ Chuyên khoa I trực tiếp soi da AI 3D miễn phí 100%, cam kết trọn gói không phát sinh phụ phí ạ).\n\nHiện tại Chị đang muốn ưu tiên xử lý mụn ẩn/mụn viêm, làm mờ sạm nám hay dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?',
        sales_stage: 'CONSULTING',
        latency_ms: 780,
        created_at: new Date(Date.now() - 25 * 60000).toISOString()
      }
    ]
  }
]

let demoConversationsState = JSON.parse(JSON.stringify(initialMockConversations))

export const getMockConversations = (pageId = null, filter = 'ALL') => {
  return demoConversationsState.filter(s => {
    if (filter === 'NEEDS_HANDOFF') return s.needs_human_handoff || s.status === 'NEEDS_HANDOFF'
    if (filter === 'HUMAN_TAKEOVER') return s.control_mode === 'HUMAN_TAKEOVER'
    if (filter === 'BOT_ACTIVE') return s.control_mode === 'BOT_ACTIVE'
    if (filter === 'HAS_PHONE') return Boolean(s.captured_phone)
    return true
  })
}

export const getMockConversationDetail = (id) => {
  const found = demoConversationsState.find(s => s.id === id) || demoConversationsState[0]
  if (found) found.unread_count = 0
  return {
    session: found,
    messages: found ? found.messages : []
  }
}






