-- V5: Dọn dẹp các chunk rỗng chỉ chứa tiêu đề mục cha (không có nội dung thân bài)
-- và đồng bộ toàn diện page_id giữa bảng documents và chunks.

-- 1. Đồng bộ page_id cho mọi chunk nếu tài liệu đã được gán Page
UPDATE chunks c
SET page_id = d.page_id
FROM documents d
WHERE c.document_id = d.id
  AND c.page_id IS DISTINCT FROM d.page_id;

-- 2. Xóa các chunk rác chỉ chứa đúng 1 dòng tiêu đề mục cha (không có ký tự xuống dòng '\n')
-- khi trong cùng tài liệu đã có các chunk con kế thừa tiêu đề đó trong heading_path.
DELETE FROM chunks parent
WHERE strpos(parent.text, chr(10)) = 0
  AND array_length(parent.heading_path, 1) = 1
  AND btrim(parent.text) = btrim(parent.heading_path[1])
  AND EXISTS (
      SELECT 1
      FROM chunks child
      WHERE child.document_id = parent.document_id
        AND child.id <> parent.id
        AND array_length(child.heading_path, 1) > 1
        AND child.heading_path[1] = parent.heading_path[1]
  );
