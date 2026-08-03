@echo off
cd /d "C:\Users\Anwender\StudioProjects\streamvault\.public-export\torve-public-candidate\website"
echo starting %date% %time% > torve-preview-server.log
"C:\Program Files\nodejs\node.exe" scripts\serve-site.mjs --root dist --port 4173 >> torve-preview-server.log 2>&1
echo exited %errorlevel% %date% %time% >> torve-preview-server.log
