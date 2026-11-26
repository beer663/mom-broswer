import json

release = json.loads(subprocess.check_output([
    "curl", "-fsSL", "--retry", "3",
    "https://api.github.com/repos/gorhill/uBlock/releases/latest",
]))

assets = [
    asset for asset in release["assets"]
    if asset["name"].endswith(".firefox.signed.xpi")
]
if len(assets) != 1:
    raise SystemExit("Expected one signed Firefox package in the latest stable release.")

asset = assets[0]
digest = asset.get("digest") or ""
if not re.fullmatch(r"sha256:[a-f0-9]{64}", digest):
    raise SystemExit("The uBlock Origin release has no valid SHA-256 digest.")

xpi = subprocess.check_output([
    "curl", "-fsSL", "--retry", "3", asset["browser_download_url"]
])
if "sha256:" + hashlib.sha256(xpi).hexdigest() != digest:
    raise SystemExit("uBlock Origin release checksum mismatch.")

print(f'Bundling uBlock Origin {release["tag_name"]}.')
