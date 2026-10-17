# Support assistant eval (gemini-3.5-flash)

Each cell: rank of the expected article among the retrieved files (#1 = best) / cited in the answer / escalation decision correct.

| Ticket | Question | Expected article | FAQ only | With ticket |
|---|---|---|---|---|
| T-101 | Почему не работает авторизация? | 02 | #2 ✅ ✅ | #1 ✅ ✅ |
| T-102 | Почему не работает авторизация? | 01 | #1 ✅ ✅ | #1 ✅ ✅ |
| T-103 | Почему не приходит сводка? | 04 | #1 ✅ ✅ | #1 ✅ ✅ |
| T-104 | Why does the app not connect? | 05 | #1 ✅ ✅ | #1 ✅ ✅ |
| T-105 | Почему чат иногда не отвечает? | 03 | #2 ✅ ✅ | #1 ✅ ✅ |
| T-106 | Можно ли это восстановить? | 06 or 10 | #3 ❌ ❌ | #1 ✅ ✅ |
| T-107 | Is it broken? | 07 | #2 ❌ ❌ | #2 ✅ ✅ |
| T-108 | What should we tell the customer? | 09 | #2 ❌ ✅ | #1 ✅ ✅ |
| T-109 | Can we refund this? | 10 | #2 ✅ ✅ | #1 ✅ ✅ |
| T-110 | Что ответить клиенту? | 10 | #3 ❌ ✅ | #1 ✅ ✅ |
| T-111 | Что посоветовать? | 10 | #2 ❌ ❌ | #1 ✅ ❌ |

| Metric | FAQ only | With ticket |
|---|---|---|
| Expected article ranked first | 3/11 | 10/11 |
| Mean rank of the expected article | 1.9 | 1.1 |
| Expected article cited | 6/11 | 11/11 |
| Escalation decision correct | 8/11 | 10/11 |
| "I don't know" answers | 5/11 | 0/11 |
