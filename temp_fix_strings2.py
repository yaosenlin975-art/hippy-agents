# Read the file as bytes, decode with UTF-8, fix, and write back
cn_file = r'd:\WorkSpaces\hippy-agents\app\src\main\res\values\strings.xml'

# Read as raw bytes
with open(cn_file, 'rb') as f:
    raw = f.read()

# Try to decode - the file might have mixed encodings now
# Let's find the last good content before the corruption
# The corruption starts around the skill_pool entries

# Let's read line by line and fix
lines = raw.decode('utf-8', errors='replace').split('\n')

fixed_lines = []
for line in lines:
    stripped = line.strip()
    # Fix corrupted lines
    if '技?%1$s' in line:
        line = line.replace('技?%1$s', '技能 %1$s')
    if '技?/string>' in line:
        line = line.replace('技?/string>', '技能</string>')
    # Skip the broken </resources> on line with content after it
    if stripped.startswith('</resources>') and len(stripped) > len('</resources>'):
        # This is the broken line - skip it
        continue
    # Skip broken chat_tts_failed and store_source_all (we'll add correct ones)
    if 'chat_tts_failed' in line and '璇' in line:
        continue
    if 'store_source_all' in line and '鍏' in line:
        continue
    fixed_lines.append(line)

# Now find the last </resources> and insert our strings before it
result = '\n'.join(fixed_lines)

# Remove trailing </resources> to add our strings before it
if result.rstrip().endswith('</resources>'):
    result = result.rstrip()[:-len('</resources>')]

# Add our new strings and close
result += '    <string name="chat_tts_failed">语音播报失败: %1$s</string>\n'
result += '    <string name="store_source_all">全部</string>\n'
result += '</resources>\n'

with open(cn_file, 'w', encoding='utf-8') as f:
    f.write(result)

print("CN strings.xml fully fixed")

# Fix EN file
en_file = r'd:\WorkSpaces\hippy-agents\app\src\main\res\values-en\strings.xml'
with open(en_file, 'rb') as f:
    raw = f.read()

lines = raw.decode('utf-8', errors='replace').split('\n')
fixed_lines = []
for line in lines:
    stripped = line.strip()
    if stripped.startswith('</resources>') and len(stripped) > len('</resources>'):
        continue
    if 'chat_tts_failed' in line and '%1<' in line:
        continue
    if 'store_source_all' in line and line.strip().startswith('<string') and 'All' in line:
        # Keep only if it's a proper line
        if '</resources>' not in line:
            fixed_lines.append(line)
        continue
    fixed_lines.append(line)

result = '\n'.join(fixed_lines)

if result.rstrip().endswith('</resources>'):
    result = result.rstrip()[:-len('</resources>')]

result += '    <string name="chat_tts_failed">TTS failed: %1$s</string>\n'
result += '    <string name="store_source_all">All</string>\n'
result += '</resources>\n'

with open(en_file, 'w', encoding='utf-8') as f:
    f.write(result)

print("EN strings.xml fully fixed")
