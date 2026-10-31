> ⚠️ DEPRECATED ARCHITECTURE (DO NOT USE)
> Tài liệu này thuộc kiến trúc V1/C1-C3 cũ (Stage cứng / Regex tiền-LLM / Template cứng) và đã bị CÁCH LY. Nguồn chân lý duy nhất hiện hành là `ACI_V2_SOURCE_OF_TRUTH.md`.

# User Test Package (Hướng Dẫn Kiểm Thử Nghiệm Thu Thực Tế)
- **Timestamp**: `2026-10-23T08:32:30Z`
- **Frontend Chat UI**: `http://localhost:3000`
- **Backend Fanpage Bot API**: `POST http://localhost:8090/api/bot/fanpage/message`
- **Target Page ID**: `b23cd382-4f7a-4829-8c54-1d410da14bf8` (Nàng Sen Spa)

## Quick cURL Smoke Test
```bash
curl -s -X POST http://localhost:8090/api/bot/fanpage/message \
  -H "Content-Type: application/json" \
  -d '{
    "page_id": "b23cd382-4f7a-4829-8c54-1d410da14bf8",
    "sender_id": "manual_uat_user_01",
    "customer_name": "Chị Lan",
    "message": "Dạo này chị ngồi máy tính nhiều mỏi cứng cổ vai gáy quá shop ơi",
    "history": []
  }' | jq .
```
