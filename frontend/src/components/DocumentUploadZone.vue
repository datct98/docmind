<template>
  <div 
    class="upload-container"
    :class="{ 'is-dragging': isDragging, 'is-uploading': isUploading }"
    @dragover.prevent="onDragOver"
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
    @click="triggerFileInput"
  >
    <input 
      ref="fileInputRef"
      type="file" 
      class="hidden-input" 
      accept=".pdf,.docx,.pptx,.xlsx,.md,.html,.txt" 
      @change="handleFileSelect"
    />

    <div class="upload-content">
      <div class="upload-icon-wrapper">
        <svg v-if="!isUploading" xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="upload-icon">
          <path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242"/>
          <path d="M12 12v9"/>
          <path d="m16 16-4-4-4 4"/>
        </svg>
        <div v-else class="upload-spinner"></div>
      </div>

      <div class="upload-text">
        <h4 v-if="!isUploading" class="upload-title">
          Kéo thả tệp hoặc <span class="highlight">Chọn từ máy</span>
        </h4>
        <h4 v-else class="upload-title text-indigo">
          Đang tải lên & chuyển giao parser...
        </h4>
        <p class="upload-subtitle">Hỗ trợ PDF, DOCX, PPTX, XLSX, MD, HTML (tối đa 50MB)</p>
      </div>
    </div>

    <!-- Quick Template Bar -->
    <div class="template-actions-bar" @click.stop>
      <button type="button" class="btn-template" @click="downloadStandardTemplate" title="Tải file mẫu chuẩn cấu trúc 5 phần cho Sale (.md)">
        📥 Tải Template chuẩn (.md)
      </button>
      <button type="button" class="btn-template btn-template-accent" :disabled="isUploading" @click="uploadSampleTemplateNow" title="Đẩy ngay file Template mẫu vào Page hiện tại để test hỏi đáp">
        ⚡ Nạp thử file mẫu ngay
      </button>
    </div>

    <!-- Error notice -->
    <div v-if="uploadError" class="upload-error" @click.stop>
      <span>{{ uploadError }}</span>
      <button class="btn-clear-error" @click="uploadError = ''">×</button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  isUploading: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['file-selected'])

const fileInputRef = ref(null)
const isDragging = ref(false)
const uploadError = ref('')

const STANDARD_SALES_TEMPLATE_MD = `# 1. THÔNG TIN CHUNG VỀ PAGE & TỆP KHÁCH HÀNG
- **Tên thương hiệu / Page:** Thẩm mỹ viện & Chăm sóc da Aura Spa
- **Ngành hàng:** Dịch vụ làm đẹp, điều trị da & Dược mỹ phẩm
- **Đối tượng khách hàng mục tiêu:** Nữ giới từ 22 - 45 tuổi (nhân viên văn phòng, sau sinh) quan tâm trị mụn, trẻ hóa da an toàn
- **Nguyên tắc xưng hô & Giọng điệu tư vấn:** Xưng "Em" - gọi khách là "Chị", giọng điệu tận tâm, chuyên nghiệp, luôn báo rõ giá trọn gói và cam kết không phát sinh chi phí ẩn.

# 2. DANH MỤC SẢN PHẨM / DỊCH VỤ & BẢNG GIÁ NIÊM YẾT
## 2.1. Bảng giá các gói liệu trình chăm sóc & điều trị da
| Mã DV | Tên Gói Dịch Vụ | Đơn Giá Niêm Yết (VNĐ) | Thời Lượng / Buổi | Đối Tượng Phù Hợp | Quyền Lợi & Quà Tặng Kèm |
| :--- | :--- | :--- | :--- | :--- | :--- |
| SPA-01 | Liệu trình Trị Mụn Chuẩn Y Khoa | 1.500.000 | 60 phút | Da mụn ẩn, mụn viêm | Tặng 01 tuýp chấm mụn kháng viêm 10ml |
| SPA-02 | Căng bóng & Trẻ hóa da Meso Collagen | 3.200.000 | 90 phút | Da khô, lão hóa, từ 28 tuổi | Tặng mặt nạ phục hồi sinh học B5 |
| SPA-03 | Phác đồ Trị Nám & Tàn Nhang Laser Pico | 4.500.000 | 75 phút | Da nám mảng, nám chân sâu | Bảo hành tái phát 12 tháng bằng văn bản |
| SPA-04 | Gói Chăm sóc da Cơ bản Detox Oxy | 650.000 | 45 phút | Mọi loại da, khách mới | Miễn phí soi da AI 3D trước liệu trình |

# 3. CHÍNH SÁCH CHIẾT KHẤU & CHƯƠNG TRÌNH KHUYẾN MÃI
## 3.1. Chiết khấu theo Combo số buổi (Bậc thang số lượng)
| Hạng Combo | Số Buổi Đăng Ký | Mức Chiết Khấu (%) | Điều Kiện Thanh Toán | Quà Tặng Thêm |
| :--- | :--- | :--- | :--- | :--- |
| Buổi lẻ | 1 - 2 buổi | 0% | Thanh toán từng buổi | Miễn phí soi da |
| Combo Tiết Kiệm | Từ 3 - 4 buổi | Giảm 10% | Thanh toán 100% trong ngày | Tặng 01 buổi Detox Oxy (SPA-04) |
| Combo Toàn Diện | Từ 5 - 9 buổi | Giảm 15% | Thanh toán 100% hoặc trả góp 0% | Tặng bộ kit chăm sóc da tại nhà |
| Combo VIP Năm | Từ 10 buổi trở lên | Giảm 20% | Thanh toán tối thiểu 70% đợt 1 | Nâng hạng thẻ VIP Vàng ngay lập tức |

## 3.2. Nguyên tắc áp dụng & Điều khoản loại trừ chiết khấu
- **Nguyên tắc tính giá:** Mức chiết khấu theo Combo được tính trực tiếp trên **Đơn Giá Niêm Yết trước thuế VAT**.
- **Điều khoản cộng dồn:** Không áp dụng cộng dồn đồng thời chương trình giảm giá Combo với Voucher Sinh Nhật hoặc chương trình Flash Sale giờ vàng (khách hàng được chọn 01 ưu đãi có lợi nhất).
- **Khách hàng VIP Vàng (chi tiêu tích lũy từ 20.000.000 VNĐ):** Được giảm thêm cố định 5% trên tổng hóa đơn sau chiết khấu Combo và miễn phí phòng dịch vụ riêng (Private Room).

# 4. QUY ĐỊNH BẢO HÀNH, ĐỔI GÓI & HOÀN TIỀN
## 4.1. Chính sách đổi gói dịch vụ hoặc hoàn tiền
- **Thời hạn hỗ trợ đổi gói:** Trong vòng 07 ngày kể từ ngày thực hiện buổi đầu tiên.
- **Điều kiện hoàn tiền 100%:** Khách hàng có dấu hiệu kích ứng cơ địa được bác sĩ da liễu của Spa xác nhận không thể tiếp tục liệu trình.
- **Chuyển nhượng thẻ liệu trình:** Khách hàng được phép chuyển nhượng số buổi còn lại trong thẻ Combo cho người thân (miễn phí sang tên lần đầu).

# 5. KỊCH BẢN XỬ LÝ TÌNH HUỐNG & CÂU HỎI THƯỜNG GẶP (FAQ SALE)
## 5.1. Khi khách hàng hỏi "Giá báo đã trọn gói chưa, có phải tip cho kỹ thuật viên không?"
- **Câu trả lời chuẩn:** Dạ toàn bộ mức giá bên em báo cho Chị là **giá trọn gói 100%**, đã bao gồm dược mỹ phẩm chính hãng dùng trong suốt buổi làm và bác sĩ thăm khám. Bên em có quy định văn hóa **không nhận tiền tip (phụ phí)** dưới mọi hình thức nên Chị hoàn toàn yên tâm ạ.
`

const downloadStandardTemplate = () => {
  const blob = new Blob([STANDARD_SALES_TEMPLATE_MD], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'Template_Tri_Thuc_Sale_Chuan.md'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

const uploadSampleTemplateNow = () => {
  if (props.isUploading) return
  const file = new File(
    [STANDARD_SALES_TEMPLATE_MD],
    'Template_Tri_Thuc_Sale_AuraSpa.md',
    { type: 'text/markdown' }
  )
  validateAndEmit(file)
}

const onDragOver = () => {
  isDragging.value = true
}

const onDragLeave = () => {
  isDragging.value = false
}

const onDrop = (e) => {
  isDragging.value = false
  if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
    validateAndEmit(e.dataTransfer.files[0])
  }
}

const triggerFileInput = () => {
  if (props.isUploading) return
  fileInputRef.value?.click()
}

const handleFileSelect = (e) => {
  if (e.target.files && e.target.files.length > 0) {
    validateAndEmit(e.target.files[0])
    e.target.value = '' // reset input
  }
}

const validateAndEmit = (file) => {
  uploadError.value = ''
  if (!file) return

  const maxSize = 50 * 1024 * 1024 // 50MB
  if (file.size > maxSize) {
    uploadError.value = 'Kích thước tệp vượt quá 50MB cho phép.'
    return
  }

  emit('file-selected', file)
}
</script>

<style scoped>
.upload-container {
  border: 1.5px dashed #A8C7FA;
  border-radius: var(--radius-md);
  padding: 12px 12px 10px;
  background: #F8FAFD;
  cursor: pointer;
  transition: all 0.18s ease;
  position: relative;
  overflow: hidden;
}

.upload-container:hover {
  border-color: var(--accent-primary);
  background: #E8F0FE;
}

.upload-container.is-dragging {
  border-color: var(--accent-primary);
  background: #D3E3FD;
  transform: scale(1.01);
}

.upload-container.is-uploading {
  border-color: var(--accent-primary);
  cursor: wait;
}

.hidden-input {
  display: none;
}

.upload-content {
  display: flex;
  align-items: center;
  gap: 12px;
}

.upload-icon-wrapper {
  width: 40px;
  height: 40px;
  min-width: 40px;
  border-radius: var(--radius-md);
  background: #D3E3FD;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--accent-primary);
}

.upload-icon {
  stroke: var(--accent-primary);
}

.upload-spinner {
  width: 20px;
  height: 20px;
  border: 2.5px solid #D3E3FD;
  border-top-color: var(--accent-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.upload-text {
  flex: 1;
  text-align: left;
}

.upload-title {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--text-main);
  margin-bottom: 2px;
}

.upload-title .highlight {
  color: var(--accent-primary);
  font-weight: 700;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.upload-subtitle {
  font-size: 0.7rem;
  color: var(--text-dim);
}

.text-indigo {
  color: var(--accent-primary);
}

.template-actions-bar {
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px dashed #DDE3EA;
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-template {
  flex: 1;
  font-size: 0.7rem;
  font-weight: 600;
  padding: 5px 7px;
  border-radius: var(--radius-sm);
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.btn-template:hover:not(:disabled) {
  background: #F0F4F9;
  color: var(--text-main);
}

.btn-template-accent {
  background: #D3E3FD;
  border-color: #A8C7FA;
  color: #041E49;
}

.btn-template-accent:hover:not(:disabled) {
  background: var(--accent-primary);
  color: #ffffff;
}

.upload-error {
  margin-top: 10px;
  padding: 6px 10px;
  background: var(--status-failed-bg);
  border: 1px solid var(--status-failed-border);
  border-radius: var(--radius-sm);
  font-size: 0.75rem;
  color: var(--status-failed-text);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.btn-clear-error {
  background: none;
  border: none;
  color: var(--status-failed-text);
  font-size: 1rem;
  cursor: pointer;
  padding: 0 4px;
}
</style>

