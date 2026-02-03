import re
import sys

def bump_version(file_path):
    with open(file_path, 'r') as f:
        content = f.read()

    # Bump versionCode
    code_pattern = r'versionCode\s*=\s*(\d+)'
    code_match = re.search(code_pattern, content)
    if not code_match:
        print("Error: versionCode not found")
        sys.exit(1)
    
    current_code = int(code_match.group(1))
    new_code = current_code + 1
    content = re.sub(code_pattern, f'versionCode = {new_code}', content)

    # Bump versionName (Assumes X.Y.Z format, bumps Z)
    name_pattern = r'versionName\s*=\s*"(\d+\.\d+\.\d+)"'
    name_match = re.search(name_pattern, content)
    if not name_match:
        print("Error: versionName not found")
        sys.exit(1)
        
    current_name = name_match.group(1)
    parts = current_name.split('.')
    parts[-1] = str(int(parts[-1]) + 1)
    new_name = ".".join(parts)
    content = re.sub(name_pattern, f'versionName = "{new_name}"', content)

    with open(file_path, 'w') as f:
        f.write(content)

    print(new_name)

if __name__ == "__main__":
    bump_version("app/build.gradle.kts")
