#!/data/data/com.termux/files/usr/bin/bash
# Dùng: bash push.sh [đường_dẫn_zip]
# Lần đầu hỏi repo + token, các lần sau tự chạy.
set -e
CFG="$HOME/.pacpush"
REPO_DIR="$HOME/pacrepo"

command -v git >/dev/null || pkg install -y git
command -v unzip >/dev/null || pkg install -y unzip
[ -d "$HOME/storage/downloads" ] || termux-setup-storage

if [ ! -f "$CFG" ]; then
  read -rp "Repo (dán link hoặc owner/repo): " REPO
  read -rsp "GitHub token (quyền repo): " TOKEN; echo
  read -rp "Tên hiển thị git: " GNAME
  read -rp "Email git: " GMAIL
  printf 'REPO=%q\nTOKEN=%q\nGNAME=%q\nGMAIL=%q\n' "$REPO" "$TOKEN" "$GNAME" "$GMAIL" > "$CFG"
  chmod 600 "$CFG"
fi
. "$CFG"
# Chuẩn hóa: chấp nhận cả link đầy đủ
REPO="${REPO#https://github.com/}"; REPO="${REPO#http://github.com/}"
REPO="${REPO%/}"; REPO="${REPO%.git}"

ZIP="${1:-$(ls -t "$HOME"/storage/downloads/PACProxyVPN-v2*.zip 2>/dev/null | head -1)}"
[ -f "$ZIP" ] || { echo "Không thấy PACProxyVPN-v2.zip. Chạy: bash push.sh /đường/dẫn/file.zip"; exit 1; }
unzip -l "$ZIP" | grep -q "app/build.gradle.kts" || { echo "✗ Zip này không phải source dự án (thiếu app/build.gradle.kts)"; exit 1; }
echo "→ Dùng: $ZIP"

TMP="$(mktemp -d)"
unzip -q "$ZIP" -d "$TMP"
SRC="$TMP/PACProxyVPN"; [ -d "$SRC" ] || SRC="$TMP"

mkdir -p "$REPO_DIR"; cd "$REPO_DIR"
[ -d .git ] || git init -q
git config user.name "$GNAME"; git config user.email "$GMAIL"
git branch -M main
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -a "$SRC"/. "$REPO_DIR"/
rm -rf "$TMP"

git remote remove origin 2>/dev/null || true
git remote add origin "https://$TOKEN@github.com/$REPO.git"
git add -A
git commit -qm "Update PAC Proxy VPN $(date +%F_%H-%M)" || echo "Không có thay đổi mới"
git push -u origin main --force
echo "✓ Đã push. Vào GitHub → Actions → tải artifact PACProxyVPN-debug"
