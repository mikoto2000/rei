cd F:\project\rei
Remove-Item inspect_tool.ps1, list_jars.ps1, run_tests.ps1 -ErrorAction SilentlyContinue
Remove-Item component.puml -ErrorAction SilentlyContinue
git add -A
git commit -m "feat(event): wrap MCP ToolCallbackProvider with ToolEventCallbackProvider in configuration"
