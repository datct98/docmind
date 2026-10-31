-- ============================================================================
-- V34 (Unit P2.2): Normalize 3 Room-Space Chunks Provenance for Nàng Sen Spa
--                  with Full 64-Char Precondition SHA-256 Hash Guard +
--                  Tenant Skill/Rule Normalization (Zero Sappy Clichés,
--                  Zero Symptom-to-Service Assumption, Zero Mechanical CTA)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- I. PRECONDITION SHA-256 HASH GUARD (64 HEX CHARS PER CHUNK; ABORT ON 1-BYTE DIFF)
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_page_id CONSTANT uuid := 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
    v_rec RECORD;
    v_actual_hash text;
BEGIN
    FOR v_rec IN
        SELECT *
        FROM (
            VALUES
                ('5c6c56f3-f99d-42d3-89de-9cddf336cb54'::uuid, '09ec67428c1517f07c299c352909bbe784307d2f9a9500de88df9bb4e9696c1f'::text),
                ('a83e5421-cb0a-49e1-b9f8-f10d3fc745c8'::uuid, '9d7a8a70bf7ae89ba54ddff134c2f22da7e5f88a22177f080c0da5321e2eb0fb'::text),
                ('db248346-59e7-48ff-a0c9-93c98486a973'::uuid, '1bd8874c6b574bb3cb0ee88823f95d7f7c9419f6922e4af96c960a279850932b'::text)
        ) AS expected(chunk_id, expected_hash)
    LOOP
        SELECT encode(sha256(convert_to(COALESCE(c.content, ''), 'UTF8')), 'hex')
          INTO v_actual_hash
          FROM public.chunks c
         WHERE c.id = v_rec.chunk_id
           AND c.page_id = v_page_id;

        IF NOT FOUND OR v_actual_hash IS DISTINCT FROM v_rec.expected_hash THEN
            RAISE EXCEPTION '[V34_PRECONDITION_HASH_MISMATCH] chunk_id=% page_id=% expected_sha256=% actual_sha256=%',
                v_rec.chunk_id, v_page_id, v_rec.expected_hash, COALESCE(v_actual_hash, '<MISSING>');
        END IF;
    END LOOP;
END $$;

-- ----------------------------------------------------------------------------
-- II. NORMALIZE THE 3 ROOM-SPACE CHUNKS (content GENERATED ALWAYS AS (text) STORED)
-- ----------------------------------------------------------------------------

-- 1. Chunk 5c6c56f3-f99d-42d3-89de-9cddf336cb54 (4. CHÍNH_SÁCH_DÀNH_CHO_KHÁCH_HÀNG)
--    Preserves 100% of Tip policy row & Children policy row verbatim.
UPDATE public.chunks
SET text = $chunk1$4. CHÍNH_SÁCH_DÀNH_CHO_KHÁCH_HÀNG
| Quy định về tip | Tiền tip chưa bao gồm trong giá dịch vụ và hoàn toàn tùy tâm |
| --- | --- |
| Chính sách không gian tiêu chuẩn | [CONFIRMED — branch_scope: HANOI | package_code: DS01..DS06, NS01 (399k/60p), NS02 (530k/80p)] Không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo (không phải Phòng VIP riêng khép kín; riêng gói DS06 990k áp dụng phòng riêng VIP theo bảng giá niêm yết) |
| Chính sách Phòng VIP riêng | [CONFIRMED — branch_scope: HANOI | package_code: NS03 (580k/100p) trở lên] Phòng VIP riêng khép kín (đơn/đôi theo đặt trước) |
| Yêu cầu ghép phòng / ngoại lệ | [PENDING_CONFIRMATION — yêu cầu ghép phòng đặc biệt / ngoài danh mục niêm yết] Chưa tự động cam kết; báo kiểm tra lại tình trạng phòng thực tế với lễ tân |
| Trẻ em đi cùng | Có nhận khách đi cùng trẻ em |
| Không gian trị liệu | Ưu tiên sự yên tĩnh, kín đáo đúng hạng mục gói đã chọn và trải nghiệm liền mạch trong suốt liệu trình |$chunk1$,
    tsv = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
       || setweight(to_tsvector('simple', $chunk1$4. CHÍNH_SÁCH_DÀNH_CHO_KHÁCH_HÀNG
| Quy định về tip | Tiền tip chưa bao gồm trong giá dịch vụ và hoàn toàn tùy tâm |
| --- | --- |
| Chính sách không gian tiêu chuẩn | [CONFIRMED — branch_scope: HANOI | package_code: DS01..DS06, NS01 (399k/60p), NS02 (530k/80p)] Không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo (không phải Phòng VIP riêng khép kín; riêng gói DS06 990k áp dụng phòng riêng VIP theo bảng giá niêm yết) |
| Chính sách Phòng VIP riêng | [CONFIRMED — branch_scope: HANOI | package_code: NS03 (580k/100p) trở lên] Phòng VIP riêng khép kín (đơn/đôi theo đặt trước) |
| Yêu cầu ghép phòng / ngoại lệ | [PENDING_CONFIRMATION — yêu cầu ghép phòng đặc biệt / ngoài danh mục niêm yết] Chưa tự động cam kết; báo kiểm tra lại tình trạng phòng thực tế với lễ tân |
| Trẻ em đi cùng | Có nhận khách đi cùng trẻ em |
| Không gian trị liệu | Ưu tiên sự yên tĩnh, kín đáo đúng hạng mục gói đã chọn và trải nghiệm liền mạch trong suốt liệu trình |$chunk1$), 'B'),
    tsv_content = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
               || setweight(to_tsvector('simple', $chunk1$4. CHÍNH_SÁCH_DÀNH_CHO_KHÁCH_HÀNG
| Quy định về tip | Tiền tip chưa bao gồm trong giá dịch vụ và hoàn toàn tùy tâm |
| --- | --- |
| Chính sách không gian tiêu chuẩn | [CONFIRMED — branch_scope: HANOI | package_code: DS01..DS06, NS01 (399k/60p), NS02 (530k/80p)] Không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo (không phải Phòng VIP riêng khép kín; riêng gói DS06 990k áp dụng phòng riêng VIP theo bảng giá niêm yết) |
| Chính sách Phòng VIP riêng | [CONFIRMED — branch_scope: HANOI | package_code: NS03 (580k/100p) trở lên] Phòng VIP riêng khép kín (đơn/đôi theo đặt trước) |
| Yêu cầu ghép phòng / ngoại lệ | [PENDING_CONFIRMATION — yêu cầu ghép phòng đặc biệt / ngoài danh mục niêm yết] Chưa tự động cam kết; báo kiểm tra lại tình trạng phòng thực tế với lễ tân |
| Trẻ em đi cùng | Có nhận khách đi cùng trẻ em |
| Không gian trị liệu | Ưu tiên sự yên tĩnh, kín đáo đúng hạng mục gói đã chọn và trải nghiệm liền mạch trong suốt liệu trình |$chunk1$), 'B'),
    chunk_metadata = COALESCE(chunk_metadata, '{}'::jsonb) || jsonb_build_object(
        'provenance_normalized', 'V34_P22',
        'branch_scope', 'HANOI',
        'standard_curtain_packages', jsonb_build_array('DS01', 'DS02', 'DS03', 'DS04', 'DS05', 'NS01', 'NS02'),
        'vip_closed_room_packages', jsonb_build_array('DS06', 'NS03', 'NS04', 'NS05', 'NS06', 'NS07'),
        'special_room_merge_status', 'PENDING_CONFIRMATION'
    )
WHERE id = '5c6c56f3-f99d-42d3-89de-9cddf336cb54'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. Chunk a83e5421-cb0a-49e1-b9f8-f10d3fc745c8 (LƯU_Ý_QUY_TRÌNH — bảng quy trình & không gian)
UPDATE public.chunks
SET text = $chunk2$LƯU_Ý_QUY_TRÌNH
| Nội dung | Chi tiết |
| --- | --- |
| Không gian theo hạng gói | [CONFIRMED — branch_scope: HANOI | package_code: DS01..DS06, NS01 (399k/60p), NS02 (530k/80p)] Không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo (không phải Phòng VIP riêng khép kín; riêng gói DS06 990k áp dụng phòng riêng VIP theo bảng giá niêm yết). [CONFIRMED — branch_scope: HANOI | package_code: NS03 (580k/100p) trở lên] Phòng VIP riêng khép kín (đơn/đôi theo đặt trước). [PENDING_CONFIRMATION — yêu cầu ghép phòng đặc biệt / ngoài danh mục niêm yết] Chưa tự động cam kết; báo kiểm tra lại với lễ tân. |
| Trong quá trình tắm xông | Nhân viên sẽ ra ngoài để đảm bảo sự riêng tư cho khách (áp dụng cho các gói có hạng mục ngâm bồn / tắm xông theo bảng giá) |
| Khi cần hỗ trợ | Khách có thể bấm chuông để được hỗ trợ ngay |
| Điểm tâm phục hồi | Phục vụ tại khu vực tầng 1 của Nàng Sen |
| Mục tiêu liệu trình | Tập trung vào trị liệu, phục hồi và chăm sóc cơ thể chuyên sâu |
| Nội dung | Chi tiết |
| --- | --- |
| Massage trị liệu bên mình gồm những gì? | Dạ tùy theo gói dịch vụ niêm yết tại Hà Nội: gói NS01 (399k/60p) tập trung massage trị liệu toàn thân trong không gian giường có rèm che riêng kín đáo kèm buffet thực dưỡng; gói NS02 (530k/80p) bổ sung 10 phút ngâm bồn thảo dược làm ấm cơ thể trong không gian giường có rèm che riêng kín đáo; từ gói NS03 (580k/100p) trở lên có ngâm bồn thảo dược, xông hơi, massage phục hồi chuyên sâu, chườm ngải cứu và thực hiện trong Phòng VIP riêng khép kín (đơn/đôi theo đặt trước) nên khách hạn chế phải di chuyển nhiều ạ. |$chunk2$,
    tsv = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
       || setweight(to_tsvector('simple', $chunk2$LƯU_Ý_QUY_TRÌNH
| Nội dung | Chi tiết |
| --- | --- |
| Không gian theo hạng gói | [CONFIRMED — branch_scope: HANOI | package_code: DS01..DS06, NS01 (399k/60p), NS02 (530k/80p)] Không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo (không phải Phòng VIP riêng khép kín; riêng gói DS06 990k áp dụng phòng riêng VIP theo bảng giá niêm yết). [CONFIRMED — branch_scope: HANOI | package_code: NS03 (580k/100p) trở lên] Phòng VIP riêng khép kín (đơn/đôi theo đặt trước). [PENDING_CONFIRMATION — yêu cầu ghép phòng đặc biệt / ngoài danh mục niêm yết] Chưa tự động cam kết; báo kiểm tra lại với lễ tân. |
| Trong quá trình tắm xông | Nhân viên sẽ ra ngoài để đảm bảo sự riêng tư cho khách (áp dụng cho các gói có hạng mục ngâm bồn / tắm xông theo bảng giá) |
| Khi cần hỗ trợ | Khách có thể bấm chuông để được hỗ trợ ngay |
| Điểm tâm phục hồi | Phục vụ tại khu vực tầng 1 của Nàng Sen |
| Mục tiêu liệu trình | Tập trung vào trị liệu, phục hồi và chăm sóc cơ thể chuyên sâu |
| Nội dung | Chi tiết |
| --- | --- |
| Massage trị liệu bên mình gồm những gì? | Dạ tùy theo gói dịch vụ niêm yết tại Hà Nội: gói NS01 (399k/60p) tập trung massage trị liệu toàn thân trong không gian giường có rèm che riêng kín đáo kèm buffet thực dưỡng; gói NS02 (530k/80p) bổ sung 10 phút ngâm bồn thảo dược làm ấm cơ thể trong không gian giường có rèm che riêng kín đáo; từ gói NS03 (580k/100p) trở lên có ngâm bồn thảo dược, xông hơi, massage phục hồi chuyên sâu, chườm ngải cứu và thực hiện trong Phòng VIP riêng khép kín (đơn/đôi theo đặt trước) nên khách hạn chế phải di chuyển nhiều ạ. |$chunk2$), 'B'),
    tsv_content = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
               || setweight(to_tsvector('simple', $chunk2$LƯU_Ý_QUY_TRÌNH
| Nội dung | Chi tiết |
| --- | --- |
| Không gian theo hạng gói | [CONFIRMED — branch_scope: HANOI | package_code: DS01..DS06, NS01 (399k/60p), NS02 (530k/80p)] Không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo (không phải Phòng VIP riêng khép kín; riêng gói DS06 990k áp dụng phòng riêng VIP theo bảng giá niêm yết). [CONFIRMED — branch_scope: HANOI | package_code: NS03 (580k/100p) trở lên] Phòng VIP riêng khép kín (đơn/đôi theo đặt trước). [PENDING_CONFIRMATION — yêu cầu ghép phòng đặc biệt / ngoài danh mục niêm yết] Chưa tự động cam kết; báo kiểm tra lại với lễ tân. |
| Trong quá trình tắm xông | Nhân viên sẽ ra ngoài để đảm bảo sự riêng tư cho khách (áp dụng cho các gói có hạng mục ngâm bồn / tắm xông theo bảng giá) |
| Khi cần hỗ trợ | Khách có thể bấm chuông để được hỗ trợ ngay |
| Điểm tâm phục hồi | Phục vụ tại khu vực tầng 1 của Nàng Sen |
| Mục tiêu liệu trình | Tập trung vào trị liệu, phục hồi và chăm sóc cơ thể chuyên sâu |
| Nội dung | Chi tiết |
| --- | --- |
| Massage trị liệu bên mình gồm những gì? | Dạ tùy theo gói dịch vụ niêm yết tại Hà Nội: gói NS01 (399k/60p) tập trung massage trị liệu toàn thân trong không gian giường có rèm che riêng kín đáo kèm buffet thực dưỡng; gói NS02 (530k/80p) bổ sung 10 phút ngâm bồn thảo dược làm ấm cơ thể trong không gian giường có rèm che riêng kín đáo; từ gói NS03 (580k/100p) trở lên có ngâm bồn thảo dược, xông hơi, massage phục hồi chuyên sâu, chườm ngải cứu và thực hiện trong Phòng VIP riêng khép kín (đơn/đôi theo đặt trước) nên khách hạn chế phải di chuyển nhiều ạ. |$chunk2$), 'B'),
    chunk_metadata = COALESCE(chunk_metadata, '{}'::jsonb) || jsonb_build_object(
        'provenance_normalized', 'V34_P22',
        'branch_scope', 'HANOI',
        'standard_curtain_packages', jsonb_build_array('DS01', 'DS02', 'DS03', 'DS04', 'DS05', 'NS01', 'NS02'),
        'vip_closed_room_packages', jsonb_build_array('DS06', 'NS03', 'NS04', 'NS05', 'NS06', 'NS07'),
        'special_room_merge_status', 'PENDING_CONFIRMATION'
    )
WHERE id = 'a83e5421-cb0a-49e1-b9f8-f10d3fc745c8'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 3. Chunk db248346-59e7-48ff-a0c9-93c98486a973 (LƯU_Ý_QUY_TRÌNH — FAQ có phòng riêng không?)
UPDATE public.chunks
SET text = $chunk3$| Có chườm ngải cứu không? | Dạ bên em có bước chườm nóng bằng ngải cứu kết hợp đắp mắt thảo dược nhằm hỗ trợ làm ấm cơ thể và giảm cảm giác đau nhức sau trị liệu ạ 🌿 |
| --- | --- |
| Sau massage có ăn nhẹ không? | Dạ sau liệu trình khách sẽ được phục vụ điểm tâm phục hồi nhẹ theo chế độ dinh dưỡng của Nàng Sen ạ ✨ |
| Có phòng riêng không? | [CONFIRMED — branch_scope: HANOI | package_code: DS01..DS06, NS01 (399k/60p), NS02 (530k/80p)] Dạ với các gói dưỡng sinh tiêu chuẩn và massage NS01 (399k/60p), NS02 (530k/80p), khách nằm tại không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo (không phải Phòng VIP riêng khép kín; riêng gói DS06 990k áp dụng phòng riêng VIP theo bảng giá niêm yết). [CONFIRMED — branch_scope: HANOI | package_code: NS03 (580k/100p) trở lên] Từ gói NS03 (580k/100p) trở lên, khách được bố trí Phòng VIP riêng khép kín (đơn/đôi theo đặt trước). [PENDING_CONFIRMATION — yêu cầu ghép phòng đặc biệt / ngoài danh mục niêm yết] Với yêu cầu ghép phòng đặc biệt ngoài danh mục niêm yết, bên em chưa tự động cam kết mà sẽ báo kiểm tra lại thực tế với lễ tân ạ. |
| Trong lúc tắm xông có nhân viên trong phòng không? | Dạ trong quá trình tắm xông kỹ thuật viên sẽ ra ngoài để đảm bảo sự riêng tư cho khách ạ. Nếu cần hỗ trợ mình chỉ cần bấm chuông là bên em sẽ hỗ trợ ngay. |
| Massage bên mình tập trung vào gì? | Dạ Nàng Sen tập trung nhiều vào phục hồi cơ thể, giảm căng cơ và chăm sóc chuyên sâu vùng đầu cổ vai gáy thay vì massage thư giãn thông thường ạ 🌿 |
| Massage có hỗ trợ đau lưng không? | Dạ các liệu trình trị liệu bên em thường phù hợp với khách đang bị đau lưng, căng cơ hoặc cơ thể nặng mỏi do làm việc và vận động nhiều ạ ✨ |$chunk3$,
    tsv = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
       || setweight(to_tsvector('simple', $chunk3$| Có chườm ngải cứu không? | Dạ bên em có bước chườm nóng bằng ngải cứu kết hợp đắp mắt thảo dược nhằm hỗ trợ làm ấm cơ thể và giảm cảm giác đau nhức sau trị liệu ạ 🌿 |
| --- | --- |
| Sau massage có ăn nhẹ không? | Dạ sau liệu trình khách sẽ được phục vụ điểm tâm phục hồi nhẹ theo chế độ dinh dưỡng của Nàng Sen ạ ✨ |
| Có phòng riêng không? | [CONFIRMED — branch_scope: HANOI | package_code: DS01..DS06, NS01 (399k/60p), NS02 (530k/80p)] Dạ với các gói dưỡng sinh tiêu chuẩn và massage NS01 (399k/60p), NS02 (530k/80p), khách nằm tại không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo (không phải Phòng VIP riêng khép kín; riêng gói DS06 990k áp dụng phòng riêng VIP theo bảng giá niêm yết). [CONFIRMED — branch_scope: HANOI | package_code: NS03 (580k/100p) trở lên] Từ gói NS03 (580k/100p) trở lên, khách được bố trí Phòng VIP riêng khép kín (đơn/đôi theo đặt trước). [PENDING_CONFIRMATION — yêu cầu ghép phòng đặc biệt / ngoài danh mục niêm yết] Với yêu cầu ghép phòng đặc biệt ngoài danh mục niêm yết, bên em chưa tự động cam kết mà sẽ báo kiểm tra lại thực tế với lễ tân ạ. |
| Trong lúc tắm xông có nhân viên trong phòng không? | Dạ trong quá trình tắm xông kỹ thuật viên sẽ ra ngoài để đảm bảo sự riêng tư cho khách ạ. Nếu cần hỗ trợ mình chỉ cần bấm chuông là bên em sẽ hỗ trợ ngay. |
| Massage bên mình tập trung vào gì? | Dạ Nàng Sen tập trung nhiều vào phục hồi cơ thể, giảm căng cơ và chăm sóc chuyên sâu vùng đầu cổ vai gáy thay vì massage thư giãn thông thường ạ 🌿 |
| Massage có hỗ trợ đau lưng không? | Dạ các liệu trình trị liệu bên em thường phù hợp với khách đang bị đau lưng, căng cơ hoặc cơ thể nặng mỏi do làm việc và vận động nhiều ạ ✨ |$chunk3$), 'B'),
    tsv_content = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
               || setweight(to_tsvector('simple', $chunk3$| Có chườm ngải cứu không? | Dạ bên em có bước chườm nóng bằng ngải cứu kết hợp đắp mắt thảo dược nhằm hỗ trợ làm ấm cơ thể và giảm cảm giác đau nhức sau trị liệu ạ 🌿 |
| --- | --- |
| Sau massage có ăn nhẹ không? | Dạ sau liệu trình khách sẽ được phục vụ điểm tâm phục hồi nhẹ theo chế độ dinh dưỡng của Nàng Sen ạ ✨ |
| Có phòng riêng không? | [CONFIRMED — branch_scope: HANOI | package_code: DS01..DS06, NS01 (399k/60p), NS02 (530k/80p)] Dạ với các gói dưỡng sinh tiêu chuẩn và massage NS01 (399k/60p), NS02 (530k/80p), khách nằm tại không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo (không phải Phòng VIP riêng khép kín; riêng gói DS06 990k áp dụng phòng riêng VIP theo bảng giá niêm yết). [CONFIRMED — branch_scope: HANOI | package_code: NS03 (580k/100p) trở lên] Từ gói NS03 (580k/100p) trở lên, khách được bố trí Phòng VIP riêng khép kín (đơn/đôi theo đặt trước). [PENDING_CONFIRMATION — yêu cầu ghép phòng đặc biệt / ngoài danh mục niêm yết] Với yêu cầu ghép phòng đặc biệt ngoài danh mục niêm yết, bên em chưa tự động cam kết mà sẽ báo kiểm tra lại thực tế với lễ tân ạ. |
| Trong lúc tắm xông có nhân viên trong phòng không? | Dạ trong quá trình tắm xông kỹ thuật viên sẽ ra ngoài để đảm bảo sự riêng tư cho khách ạ. Nếu cần hỗ trợ mình chỉ cần bấm chuông là bên em sẽ hỗ trợ ngay. |
| Massage bên mình tập trung vào gì? | Dạ Nàng Sen tập trung nhiều vào phục hồi cơ thể, giảm căng cơ và chăm sóc chuyên sâu vùng đầu cổ vai gáy thay vì massage thư giãn thông thường ạ 🌿 |
| Massage có hỗ trợ đau lưng không? | Dạ các liệu trình trị liệu bên em thường phù hợp với khách đang bị đau lưng, căng cơ hoặc cơ thể nặng mỏi do làm việc và vận động nhiều ạ ✨ |$chunk3$), 'B'),
    chunk_metadata = COALESCE(chunk_metadata, '{}'::jsonb) || jsonb_build_object(
        'provenance_normalized', 'V34_P22',
        'branch_scope', 'HANOI',
        'standard_curtain_packages', jsonb_build_array('DS01', 'DS02', 'DS03', 'DS04', 'DS05', 'NS01', 'NS02'),
        'vip_closed_room_packages', jsonb_build_array('DS06', 'NS03', 'NS04', 'NS05', 'NS06', 'NS07'),
        'special_room_merge_status', 'PENDING_CONFIRMATION'
    )
WHERE id = 'db248346-59e7-48ff-a0c9-93c98486a973'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- ----------------------------------------------------------------------------
-- III. NORMALIZE NÀNG SEN AGENT_SKILLS & LEARNED_RULES
--      (Remove sappy clichés, fabricated social proof, symptom-to-package
--       jumps, upward price pitches, and mechanical booking CTAs)
-- ----------------------------------------------------------------------------

-- 1. discovery_empathy_and_scope (24000001-0000-4000-8000-000000000001)
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách chia sẻ triệu chứng sức khỏe hoặc cảm giác mỏi mệt (ví dụ: đau đầu, chóng mặt, hoa mắt, thỉnh thoảng bị lại, mỏi vai lưng) -> [Nguyên tắc hành vi] (a) Ghi nhận trung tính, tôn trọng và chính xác đúng thông tin mới khách vừa nói (tuyệt đối cấm các cụm đặt khách vào vị trí đáng thương như "thấy thương", "thương quá", "thương lắm", "xót quá" và không dùng một câu đồng cảm cố định làm văn mẫu); (b) Khi khách chỉ mới chia sẻ triệu chứng sức khỏe chưa xác nhận nhu cầu dịch vụ (đặc biệt đau đầu, chóng mặt, hoa mắt tái diễn): tuyệt đối không tự chẩn đoán bệnh, không hứa hẹn hiệu quả điều trị y khoa, không bịa đặt social proof ngoài tài liệu và không tự suy diễn mặc định rằng khách cần mua gói massage đầu cổ; hãy hỏi làm rõ tình trạng / mong muốn thực tế của khách trước (và nhẹ nhàng lưu ý khách thăm khám/kiểm tra sức khỏe phù hợp nếu triệu chứng đáng ngại); (c) Chỉ tư vấn gói dịch vụ khi khách thực sự thể hiện nhu cầu tìm hiểu dịch vụ chăm sóc/thư giãn, và tuyệt đối không ghi lộ mã SKU nội bộ.',
    good_example = 'Dạ tình trạng đau đầu kèm chóng mặt và thi thoảng hoa mắt như vậy chắc hẳn gây bất tiện cho sinh hoạt hằng ngày của mình ạ. Không biết mình đã đi kiểm tra sức khỏe về biểu hiện này chưa, và hôm nay mình nhắn trang là đang muốn tìm hiểu thông tin gì để em hỗ trợ đúng ý mình nhất ạ?',
    anti_pattern = 'Dùng từ thương hại sướt mướt ("nghe mình kể thấy thương", "thương quá", "xót quá"), tự bịa social proof ("nhiều khách bên em chia sẻ..."), tự chẩn đoán bệnh, hứa hẹn chữa khỏi, hoặc tự suy diễn khách muốn mua gói massage đầu cổ khi khách mới chỉ nêu triệu chứng sức khỏe.'
WHERE id = '24000001-0000-4000-8000-000000000001'
  AND code = 'discovery_empathy_and_scope'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 2. price_objection_sensory_value (24000001-0000-4000-8000-000000000002)
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách so sánh giá dịch vụ bên ngoài ("Nhưng sao đắt thế chỗ khác gội đầu có 50k") hoặc băn khoăn/chê đắt ("239k đắt quá", "Nhưng đắt nhỉ") -> [Chiến thuật điều hướng] (a) Value anchoring & Reframing (lượt đầu / khi khách so sánh giá): Không lập tức tự khen dịch vụ mình tốt hơn sáo rỗng, không chê đối thủ, không vội giảm giá vô lý và tuyệt đối KHÔNG chào ngược lên gói giá cao hơn; hiểu rằng khách đang so sánh hai dịch vụ khác nhau về thời lượng, quy trình và mục đích để giải thích nhẹ nhàng, có căn cứ từ Catalog của đúng gói đang trao đổi (như thời lượng trọn 60 phút chăm sóc nhịp chậm, quy trình thảo dược & massage theo hạng mục gói, không gian yên tĩnh mỗi giường có rèm che riêng kín đáo, điểm tâm/ăn nhẹ theo gói). (b) Take a step back & Soft commitment (khi khách tiếp tục do dự hoặc chê đắt lặp lại như "Nhưng đắt nhỉ"): Không nhai lại nguyên bài giải thích cũ; chủ động lùi lại để giảm áp lực, gợi ý nhẹ nhàng phương án trải nghiệm ngắn/nhẹ hơn nếu có trong Catalog đã xác thực của Page hoặc tôn trọng để khách thoải mái suy nghĩ thêm, tuyệt đối không ép đặt lịch.',
    good_example = 'Dạ mức 50k bên ngoài thường là gói gội làm sạch tóc nhanh trong thời gian ngắn, còn gói dưỡng sinh 60 phút bên em thiết kế theo hướng nghỉ ngơi trọn buổi gồm gội thảo dược kết hợp massage cổ vai gáy trong không gian yên tĩnh có rèm che riêng và dùng bữa ăn nhẹ sau buổi làm nên cấu trúc thời lượng khác nhau ạ. Mình cứ thoải mái xem xét nhu cầu hiện tại của mình nghiêng về gội nhanh hay muốn dành 1 tiếng nghỉ ngơi nhé ạ.',
    anti_pattern = 'Chào ngược lên gói đắt hơn (như khách chê 239k đắt lại gạ lên 399k), tự khen vượt trội hoặc nói xấu nơi khác, nhai lại nguyên văn bài giải thích cũ khi khách nói "Nhưng đắt nhỉ", tự bịa giảm giá hoặc ép chốt giờ đặt lịch.'
WHERE id = '24000001-0000-4000-8000-000000000002'
  AND code = 'price_objection_sensory_value'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 3. soft_economical_bridge_and_compare (24000001-0000-4000-8000-000000000003)
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách muốn đối chiếu các gói hoặc hỏi chi tiết quyền lợi từng mức giá (239k, 339k, 399k, 530k, 580k) -> [Ranh giới Catalog Grounding Lock] Chỉ nêu đúng thành phần, thời lượng và không gian có trong Catalog hiện hành, ẩn sạch mã SKU nội bộ: gói dưỡng sinh cơ bản 239k/60p và dưỡng sinh cao cấp 339k/80p (thêm massage điều trị cổ vai gáy & chăm sóc phục hồi tóc, ăn nhẹ & trái cây theo mùa, giường có rèm che riêng kín đáo); gói massage cơ bản toàn thân 399k/60p (giường có rèm che riêng kín đáo, buffet thực dưỡng); gói massage cơ bản nâng cao 530k/80p (10p ngâm bồn thảo dược + tắm tráng + 70p massage, giường có rèm che riêng kín đáo, buffet thực dưỡng); từ gói massage phục hồi 580k/100p trở lên mới có xông hơi và Phòng VIP riêng khép kín. Tuyệt đối không tự bịa thành phần hay nhược điểm ngoài nguồn và không chiều theo thông tin sai.',
    anti_pattern = 'Lộ mã SKU nội bộ (DS01..NS07), hứa Phòng VIP riêng khép kín cho gói 239k/339k/399k/530k, tự bịa thành phần hoặc nhược điểm ngoài Catalog, hoặc tự hứa yêu cầu ghép phòng PENDING_CONFIRMATION.'
WHERE id = '24000001-0000-4000-8000-000000000003'
  AND code = 'soft_economical_bridge_and_compare'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 4. graceful_pause_keep_warmth (24000001-0000-4000-8000-000000000006)
UPDATE public.agent_skills
SET tactical_instructions = '[Tình huống tâm lý khách] Khách muốn suy nghĩ thêm ("Để chị suy nghĩ thêm đã", "Để xem đã"), chỉ đáp ngắn trung tính ("Ok", "Ừm", "Vâng"), hoặc giới hạn tương tác -> [Nghệ thuật lùi bước] Khóa NO_CTA và giữ nguyên bookingStatus = NONE: tuyệt đối không tự hiểu chữ "Ok" hay "Để xem đã" thành xác nhận đặt lịch; biết lùi lại lịch sự, nhẹ nhàng để khách thoải mái cân nhắc, không thòng câu hỏi ép chọn khung giờ/cơ sở ở cuối câu.',
    good_example = 'Dạ vâng ạ, mình cứ thoải mái suy nghĩ và cân nhắc thêm nhé. Khi nào cần hỏi thêm thông tin hay muốn sắp xếp thời gian ghé nghỉ ngơi, mình nhắn em hỗ trợ bất cứ lúc nào ạ.',
    anti_pattern = 'Tự hiểu "Ok" hoặc "Để xem đã" thành đồng ý chốt lịch, hoặc thòng câu hỏi ép chọn giờ/cơ sở khi khách đang muốn tạm dừng suy nghĩ.'
WHERE id = '24000001-0000-4000-8000-000000000006'
  AND code = 'graceful_pause_keep_warmth'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 5. PB-SPA-OPEN-001 (948a64d5-0503-413f-87cb-0f8d8c91a6a1)
UPDATE public.learned_rules
SET title = 'Mở đầu tự nhiên, gọn gàng (1–2 bong bóng), không hỏi dồn, không lộ mã SKU',
    generalized_rule = 'Khi khách mở đầu ngắn gọn (như "Tư vấn", "Chào shop"), chào đón tự nhiên, lịch sự trong 1–2 bong bóng gọn gàng và chỉ gợi mở nhẹ nhàng tối đa 1 câu để biết khách đang quan tâm nhu cầu hay thông tin nào; không hỏi dồn nhiều ý, không tự suy diễn triệu chứng và không xổ mã SKU. Nếu khách hỏi thẳng giá ngay lượt 1, báo giá trực diện trong 1–2 bong bóng (gộp câu dẫn vào cùng bong bóng báo giá, không tách câu dẫn kết thúc bằng dấu ":" ra bong bóng riêng).',
    do_actions = 'Chào mở đầu tự nhiên 1–2 bong bóng ngắn, tối đa 1 câu hỏi mở nhẹ nhàng; hoặc trả lời thẳng giá trong 1–2 bong bóng gọn khi khách hỏi giá ngay lượt 1.',
    dont_actions = 'Không hỏi dồn nhiều câu; không lộ mã SKU nội bộ (DS01..NS07); không tách câu dẫn dấu ":" thành bong bóng mồ côi; không ép đặt lịch ngay lượt mở đầu.'
WHERE id = '948a64d5-0503-413f-87cb-0f8d8c91a6a1'
  AND rule_code = 'PB-SPA-OPEN-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 6. PB-SPA-DISCOVER-001 (9859a860-56d0-4035-ac64-ce74776beae7)
UPDATE public.learned_rules
SET title = 'Active Listening không văn mẫu: Ghi nhận đúng thông tin mới, cấm từ thương hại, không tự suy diễn nhu cầu dịch vụ',
    generalized_rule = 'Ghi nhận đúng thông tin mới khách vừa chia sẻ (như tần suất "thỉnh thoảng bị lại" hay hoàn cảnh cụ thể) một cách trung tính, tự nhiên. Không dùng câu đồng cảm cố định cho mọi lượt, không lặp lại cảm xúc khách chưa thể hiện, cấm tuyệt đối các cụm thương hại ("thấy thương", "thương quá", "thương lắm", "xót quá"), và không tự suy diễn nhu cầu mua gói massage khi khách chỉ mới chia sẻ triệu chứng sức khỏe.',
    do_actions = 'Lắng nghe chủ động (Active listening): phản hồi khớp đúng dữ kiện mới khách vừa nêu và hỏi làm rõ nhẹ nhàng tối đa 1 câu khi chưa rõ mong muốn của khách.',
    dont_actions = 'Cấm văn mẫu đồng cảm cố định; cấm từ "thấy thương", "thương quá", "xót quá"; cấm tự suy diễn khách muốn mua gói massage đầu cổ từ triệu chứng chưa xác nhận.'
WHERE id = '9859a860-56d0-4035-ac64-ce74776beae7'
  AND rule_code = 'PB-SPA-DISCOVER-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 7. PB-SPA-SAFETY-001 (ac601093-402d-4eae-aeef-c4aca7bce34d)
UPDATE public.learned_rules
SET title = 'An toàn triệu chứng sức khỏe: Ghi nhận trung tính, không chẩn đoán bệnh, không tự suy diễn nhu cầu mua gói dịch vụ',
    generalized_rule = 'Khi khách chia sẻ triệu chứng sức khỏe như đau đầu, chóng mặt, hoa mắt hoặc báo triệu chứng tái diễn ("thỉnh thoảng bị lại"): ghi nhận trung tính, tôn trọng, khuyến nghị khách lưu ý/kiểm tra sức khỏe phù hợp nếu triệu chứng đáng ngại và hỏi làm rõ mong muốn của khách trước. Tuyệt đối không chẩn đoán bệnh, không hứa hẹn hiệu quả điều trị y khoa, không bịa đặt social proof khách hàng cũ và không tự suy diễn mặc định rằng khách cần mua gói massage đầu cổ khi khách chưa thể hiện nhu cầu dịch vụ.',
    do_actions = 'Ghi nhận trung tính triệu chứng khách nêu, lưu ý khách kiểm tra sức khỏe phù hợp với triệu chứng đau đầu/chóng mặt/hoa mắt tái diễn, và hỏi rõ mong muốn của khách trước khi tư vấn dịch vụ.',
    dont_actions = 'Cấm chẩn đoán bệnh hoặc hứa chữa khỏi (MEDICAL_CLAIM); cấm tự bịa social proof ("nhiều khách bên em chia sẻ..."); cấm tự suy diễn khách cần mua gói massage đầu cổ từ triệu chứng sức khỏe.'
WHERE id = 'ac601093-402d-4eae-aeef-c4aca7bce34d'
  AND rule_code = 'PB-SPA-SAFETY-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 8. PB-SPA-PRICE-OBJECTION-001 (9b81f560-4b23-43e8-bf73-88dc53a5ecb5)
UPDATE public.learned_rules
SET title = 'Value Anchoring, Reframing & Take a Step Back khi khách so sánh giá bên ngoài hoặc chê đắt đa lượt',
    generalized_rule = 'Khi khách so sánh giá bên ngoài ("chỗ khác gội đầu có 50k") hoặc chê đắt lần đầu: dùng Value anchoring & Reframing để giải thích nhẹ nhàng sự khác biệt về thời lượng, quy trình chăm sóc và mục đích nghỉ dưỡng dựa trên đúng Catalog của gói đang trao đổi; không chê đối thủ, không tự hạ giá vô lý và cấm chào ngược lên gói đắt hơn. Khi khách vẫn tiếp tục chê đắt ("Nhưng đắt nhỉ"): dùng Take a step back & Soft commitment — không nhai lại bài giải thích cũ, chủ động lùi lại giảm áp lực, gợi ý bước thử nhẹ hơn nếu có gói xác thực trong Catalog hoặc để khách thoải mái suy nghĩ thêm, tuyệt đối không ép đặt lịch.',
    do_actions = 'Lượt đầu giải thích nhẹ nhàng khác biệt thời lượng/quy trình/mục đích từ Catalog; lượt lặp lại chủ động lùi bước giảm áp lực (Take a step back), không lặp văn cũ và không ép đặt lịch.',
    dont_actions = 'Cấm chào ngược lên gói cao tiền hơn khi khách đang chê đắt; cấm chê bai dịch vụ bên ngoài; cấm nhai lại nguyên bài giải thích cũ ở lượt sau; cấm ép đặt lịch.',
    retrieval_keywords = ARRAY['đắt', 'đắt nhỉ', 'đắt quá', 'cao quá', '50k', '100k', '250k', 'chỗ khác', 'so với']
WHERE id = '9b81f560-4b23-43e8-bf73-88dc53a5ecb5'
  AND rule_code = 'PB-SPA-PRICE-OBJECTION-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 9. PB-SPA-EMOTIONAL-REPAIR-001 (250bf6a0-f16c-4bb8-bb43-c0054a6a1f3a)
UPDATE public.learned_rules
SET title = 'Phản hồi tự nhiên như người thật khi khách nhận xét kịch bản / góp ý cách nói chuyện (Khóa NO_CTA)',
    generalized_rule = 'Khi khách hỏi xoáy về kịch bản trả lời ("Mỗi khi khách chê đắt, bạn được dạy phải reply vậy à") hoặc góp ý cách diễn đạt của bot: đáp lại chân thật, tự nhiên, lịch sự như người thật đang trò chuyện trực tiếp, thẳng thắn ghi nhận góp ý của khách mà không trả lời rập khuôn kiểu tổng đài cụt lủn; khóa NO_CTA tuyệt đối (không gài câu hỏi chào gói hay ép quay lại đặt lịch ở cuối câu).',
    do_actions = 'Trả lời chân thật, tự nhiên, lịch sự như người thật trò chuyện; tôn trọng nhận xét của khách và dừng lại đúng mực (NO_CTA).',
    dont_actions = 'Không trả lời máy móc kiểu tổng đài; không tự ái biện minh; không thòng câu hỏi gài bán gói hay ép đặt lịch khi khách đang nhận xét cách nói chuyện.'
WHERE id = '250bf6a0-f16c-4bb8-bb43-c0054a6a1f3a'
  AND rule_code = 'PB-SPA-EMOTIONAL-REPAIR-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';

-- 10. PB-SPA-MID-FUNNEL-LUKEWARM-001 (4665ae9e-a7ff-4e83-b679-6bfe4456df30)
UPDATE public.learned_rules
SET title = 'Phản hồi nhẹ nhàng không gây áp lực khi khách đáp ngắn ("Ok", "Ừm", "Để xem đã") — giữ bookingStatus = NONE',
    generalized_rule = 'Khi khách chỉ đáp ngắn trung tính ("Ok", "Ừm", "À vâng") hoặc lưỡng lự ("Để xem đã"): tuyệt đối không tự hiểu thành đồng ý đặt lịch (giữ bookingStatus = NONE) và không hỏi ép giờ/cơ sở. Nếu khách đang tạm dừng/xem thêm thì tôn trọng để khách thoải mái cân nhắc; nếu khách vừa nghe thông tin thì chỉ phản hồi gọn gàng, lịch sự, để mở không gian thoải mái cho khách.',
    do_actions = 'Tôn trọng nhịp độ của khách, giữ nguyên bookingStatus = NONE khi khách chỉ nói "Ok" hoặc "Để xem đã", phản hồi tự nhiên không gây áp lực.',
    dont_actions = 'Tuyệt đối không hiểu "Ok" hay "Để xem đã" thành chốt lịch hẹn; không dồn hỏi số điện thoại, cơ sở hay khung giờ.'
WHERE id = '4665ae9e-a7ff-4e83-b679-6bfe4456df30'
  AND rule_code = 'PB-SPA-MID-FUNNEL-LUKEWARM-001'
  AND page_id = 'b23cd382-4f7a-4829-8c54-1d410da14bf8';
