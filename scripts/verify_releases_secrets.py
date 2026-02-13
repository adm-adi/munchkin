import os
import subprocess
import base64

def get_property(name):
    with open("local.properties", "r") as f:
        for line in f:
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip()
    return None

def verify_and_generate():
    keystore_path = "munchkin.keystore"
    if not os.path.exists(keystore_path):
        print(f"❌ Error: {keystore_path} not found.")
        return

    password = get_property("KEYSTORE_STORE_PASSWORD")
    if not password:
        print("❌ Error: KEYSTORE_STORE_PASSWORD not found in local.properties")
        return

    print(f"Testing password from local.properties...")
    
    # Run keytool to verify password
    # We use list to check - if password is wrong it will exit with error
    keytool_path = r"D:\Program Files\Android\jbr\bin\keytool.exe"
    cmd = [
        keytool_path, "-list", 
        "-keystore", keystore_path, 
        "-storepass", password
    ]
    
    try:
        # Run command without shell=True to avoid shell interpreting special chars
        result = subprocess.run(cmd, capture_output=True, text=True)
        
        if result.returncode == 0:
            print("SUCCESS! Password is correct.")
            
            # Generate Base64
            with open(keystore_path, "rb") as kf:
                encoded = base64.b64encode(kf.read()).decode('utf-8')
                
            print("\nHERE IS YOUR BASE64 STRING FOR GITHUB SECRETS:")
            print("==================================================")
            print(encoded)
            print("==================================================")
            print("Copy the string between the lines above and paste it into GitHub Secret 'KEYSTORE_BASE64'.")
            
        else:
            print("FAILURE! Password incorrect.")
            print("The password in local.properties does NOT match the keystore.")
            print("Error output:")
            print(result.stderr)
            
    except Exception as e:
        print(f"Exception occurred: {str(e)}")

if __name__ == "__main__":
    verify_and_generate()
