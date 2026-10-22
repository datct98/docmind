"""TDD tests for LlamaIndex structured node parsing & Vietnamese semantic embeddings."""

from __future__ import annotations

from app.embeddings import _StubProvider
from app.indexing.llamaindex_parser import expand_table_rows_to_atomic_nodes


def _cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


def test_faq_table_splits_into_isolated_atomic_qa_nodes():
    headers = ["Câu hỏi của khách", "Câu trả lời chuẩn của Nàng Sen"]
    rows = [
        headers,
        [
            "Em hay đau đầu",
            "Nếu mình thường xuyên đau đầu do áp lực công việc thì liệu trình dưỡng sinh rất phù hợp ạ.",
        ],
        [
            "Tôi bị mỏi cổ vai gáy",
            "Kỹ thuật viên sẽ ấn huyệt giải tỏa điểm bó cơ kết hợp chườm nóng ngải cứu giúp nhẹ vai rõ rệt.",
        ],
    ]
    heading_path = ["FAQ Nàng Sen", "Nhóm câu hỏi triệu chứng"]

    atomic_nodes = expand_table_rows_to_atomic_nodes(rows, heading_path)

    # Should produce 2 atomic Q&A nodes, one per row, with no cross-row contamination
    assert len(atomic_nodes) == 2
    assert "Em hay đau đầu" in atomic_nodes[0]["text"]
    assert "mỏi cổ vai gáy" not in atomic_nodes[0]["text"].lower()
    assert "Tôi bị mỏi cổ vai gáy" in atomic_nodes[1]["text"]
    assert "Em hay đau đầu" not in atomic_nodes[1]["text"]


def test_pricing_table_splits_into_summary_and_atomic_package_nodes():
    headers = ["Mã gói", "Tên gói", "Thời gian", "Giá niêm yết", "Quyền lợi chính"]
    rows = [
        headers,
        ["NS01", "Massage cơ bản", "60 phút", "399.000 VNĐ", "Massage trị liệu toàn thân + điểm tâm"],
        ["NS02", "Massage nâng cao", "80 phút", "530.000 VNĐ", "Ngâm bồn thảo dược 10p + Massage 70p"],
        ["NS03", "Massage phục hồi", "100 phút", "580.000 VNĐ", "Phòng VIP riêng + Ngâm bồn + Xông hơi + Massage 85p"],
    ]
    heading_path = ["Bảng giá Nàng Sen", "Massage trị liệu Hà Nội"]

    atomic_nodes = expand_table_rows_to_atomic_nodes(rows, heading_path)

    # 3 atomic package nodes + 1 overview node
    assert len(atomic_nodes) >= 3
    ns01_nodes = [n for n in atomic_nodes if "NS01" in n["text"] and "NS02" not in n["text"]]
    assert len(ns01_nodes) == 1
    assert "399.000 VNĐ" in ns01_nodes[0]["text"]
    assert "60 phút" in ns01_nodes[0]["text"]


def test_vietnamese_semantic_concept_embedding_clusters_related_symptoms():
    provider = _StubProvider()
    v_query = provider.embed(["tôi bị nhức mỏi bả vai do ngồi máy tính"])[0]
    v_match = provider.embed(["trị liệu căng cơ vùng cổ vai gáy cho dân văn phòng"])[0]
    v_unrel = provider.embed(["chính sách xuất hóa đơn đỏ VAT và thanh toán thẻ"])[0]

    sim_related = _cosine(v_query, v_match)
    sim_unrelated = _cosine(v_query, v_unrel)

    assert sim_related > 0.30, f"Expected semantic similarity > 0.30, got {sim_related}"
    assert sim_related > sim_unrelated + 0.20
