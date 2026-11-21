import tseslint from 'typescript-eslint';
export default tseslint.config({ignores:['dist/**','target/**','node_modules/**','src-tauri/**']},...tseslint.configs.recommended);
