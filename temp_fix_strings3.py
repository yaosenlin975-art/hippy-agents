cn_file = r'd:\WorkSpaces\hippy-agents\app\src\main\res\values\strings.xml'
with open(cn_file, 'rb') as f:
    raw = f.read()

# The corrupted bytes are from PowerShell writing with wrong encoding
# Let's find and replace the corrupted patterns by working with the raw bytes

# "技能" in UTF-8 is: e6 8a 80 e8 83 bd
# The corruption "技?" suggests some bytes were lost

# Let's just replace the known corrupted lines entirely
content = raw.decode('utf-8', errors='replace')

# Replace corrupted patterns
import re

# Fix skill_pool_install_success
content = re.sub(
    r'<string name="skill_pool_install_success">[^<]*%1\$s 安装成功</string>',
    '<string name="skill_pool_install_success">技能 %1$s 安装成功</string>',
    content
)

# Fix skill_pool_delete_title
content = re.sub(
    r'<string name="skill_pool_delete_title">[^<]*</string>',
    '<string name="skill_pool_delete_title">删除技能</string>',
    content
)

with open(cn_file, 'w', encoding='utf-8') as f:
    f.write(content)

print("Corrupted lines fixed")

# Verify
with open(cn_file, 'r', encoding='utf-8') as f:
    for i, line in enumerate(f, 1):
        if 'skill_pool_install_success' in line or 'skill_pool_delete_title' in line:
            print(f"Line {i}: {line.rstrip()}")
