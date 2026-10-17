"""Pytest configuration and shared fixtures for DocMind E2E tests."""

import pytest

from .harness.client import DocMindE2EClient
from .harness.generators import DocumentGenerators


@pytest.fixture
def client() -> DocMindE2EClient:
    """Fixture providing a configured DocMindE2EClient."""
    return DocMindE2EClient()


@pytest.fixture
def generators() -> DocumentGenerators:
    """Fixture providing synthetic document generators."""
    return DocumentGenerators()


@pytest.fixture
def sample_markdown() -> str:
    """Standard multi-section Vietnamese sales policy Markdown document."""
    return DocumentGenerators.generate_markdown(
        title="Chính Sách Bán Hàng & Chiết Khấu Toàn Quốc",
        num_sections=3,
        rows_per_table=5,
    )


@pytest.fixture
def sample_docx_bytes() -> bytes:
    """Standard Vietnamese sales pricing policy .docx document."""
    return DocumentGenerators.generate_docx_bytes()


@pytest.fixture
def sample_xlsx_350_bytes() -> bytes:
    """Standard multi-sheet .xlsx workbook containing >= 350 rows."""
    return DocumentGenerators.generate_xlsx_bytes(rows_per_sheet=350)
