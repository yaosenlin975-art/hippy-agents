import re

# Fix values/strings.xml
cn_file = r'd:\WorkSpaces\hippy-agents\app\src\main\res\values\strings.xml'
with open(cn_file, 'r', encoding='utf-8', errors='replace') as f:
    content = f.read()

# Remove the broken lines after </resources> and fix the structure
# The file has </resources> on line 1645 followed by broken content, then another </resources>
# We need to remove everything between the first </resources> and the second one,
# and insert the correct strings before </resources>

# Find the last proper </resources> tag
content = content.replace(
    '</resources>    <string name="chat_tts_failed">璇煶鎾姤澶辫触: %1</string>\n    <string name="store_source_all">鍏ㄩ儴</string>\n</resources>',
    '    <string name="chat_tts_failed">语音播报失败: %1$s</string>\n    <string name="store_source_all">全部</string>\n</resources>'
)

# Also fix the corrupted lines before
content = content.replace('技?%1$s', '技能 %1$s')
content = content.replace('技?/string>', '技能</string>')

with open(cn_file, 'w', encoding='utf-8') as f:
    f.write(content)

print("CN strings.xml fixed")

# Fix values-en/strings.xml
en_file = r'd:\WorkSpaces\hippy-agents\app\src\main\res\values-en\strings.xml'
with open(en_file, 'r', encoding='utf-8', errors='replace') as f:
    content = f.read()

content = content.replace(
    '</resources>    <string name="chat_tts_failed">TTS failed: %1</string>\n    <string name="store_source_all">All</string>\n</resources>',
    '    <string name="chat_tts_failed">TTS failed: %1$s</string>\n    <string name="store_source_all">All</string>\n</resources>'
)

with open(en_file, 'w', encoding='utf-8') as f:
    f.write(content)

print("EN strings.xml fixed")
