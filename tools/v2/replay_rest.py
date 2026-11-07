"""Local REST simulator replay. No Facebook send endpoint or real customer identity."""
import argparse
import json
import pathlib
import time
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--cases')
    parser.add_argument('--port', type=int, default=8090)
    parser.add_argument('--page', required=True, type=uuid.UUID)
    args = parser.parse_args()
    page = str(args.page)
    cases = json.loads(pathlib.Path(args.cases).read_text(encoding='utf-8')) if args.cases else [{
        'id': 'owner-failure-six-turn', 'messages': [
            'Giá', 'Đau mỏi vai gáy đau đầu', 'HN', 'Long biên',
            'ủa rồi sao, chào hỏi thì ngớ ngẩn, sau hỏi cung, giờ thông báo địa chỉ rồi im luôn',
            'rồi sao? Đọc như 1 con bot hỏi đáp vậy à, trả lời cho có thế à? mệt mỏi quá thì thôi nghỉ đi khỏi tư vấn'
        ]}]
    destination = pathlib.Path(args.output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        raise SystemExit('Refusing to overwrite prior evidence')
    for case in cases:
        sender = 'v2-lab-' + str(uuid.uuid4())
        history = []
        for index, message in enumerate(case['messages']):
            body = {'page_id': page, 'sender_id': sender, 'conversation_id': sender,
                    'message': message, 'history': history, 'reset_session': index == 0,
                    'split_price_bubbles': False}
            request = urllib.request.Request(f'http://127.0.0.1:{args.port}/api/bot/fanpage/message',
                data=json.dumps(body, ensure_ascii=False).encode(), headers={'Content-Type': 'application/json', 'X-Correlation-Id': str(uuid.uuid4())})
            started = time.monotonic()
            with urllib.request.urlopen(request, timeout=90) as response:
                answer = json.load(response)
            record = {'scenario': case['id'], 'sender': sender, 'turn': index + 1,
                      'customer': message, 'elapsed_ms': round((time.monotonic()-started)*1000),
                      'response': answer}
            with destination.open('a', encoding='utf-8') as stream:
                stream.write(json.dumps(record, ensure_ascii=False) + '\n')
            bubbles = answer.get('reply_bubbles') or answer.get('replyBubbles') or []
            texts = [bubble.get('text', '') for bubble in bubbles]
            if not texts:
                texts = [answer.get('reply_text') or answer.get('reply', '')]
            history += [{'role': 'user', 'content': message}, {'role': 'assistant', 'content': '\n'.join(texts)}]
            print(json.dumps({'scenario':case['id'],'turn':index+1,'ms':record['elapsed_ms'],
                              'texts':texts},ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
