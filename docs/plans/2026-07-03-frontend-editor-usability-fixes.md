# Frontend Editor Usability Fixes Implementation Plan

> **For Antigravity:** REQUIRED WORKFLOW: Use `.agents/workflows/execute-plan.md` to execute this plan in single-flow mode.

**Goal:** Fix the editor UI issues found during frontend inspection: toolbar overflow, compressed workflow nodes, deprecated Ant Design props, and misleading debug-entry feedback.

**Architecture:** Keep the changes local to the editor surface. Use existing ReactFlow, Ant Design, Tailwind, and Zustand patterns without adding dependencies.

**Tech Stack:** React 18, TypeScript, Vite, Ant Design 6, ReactFlow, Tailwind CSS.

---

### Task 1: Editor Header Layout

**Files:**
- Modify: `frontend/src/pages/EditorPage.tsx`

**Steps:**
- Let the header wrap instead of forcing all actions onto one row.
- Reduce editor action buttons from `large` to default size.
- Keep the workflow name and engine selector stable with responsive widths.
- Change the debug save-failure warning from "please save first" to a backend-save failure message.

**Verify:**
- Run `npm run lint`.
- Run `npx tsc --noEmit -p tsconfig.app.json`.
- Run the app and confirm no document-level horizontal scrollbar at 1280px.

### Task 2: Canvas Node Rendering

**Files:**
- Modify: `frontend/src/components/FlowCanvas.tsx`

**Steps:**
- Add a custom default workflow node renderer with stable dimensions and wrapped labels.
- Keep condition nodes unchanged.
- Use shared default-node dimensions for drop positioning.

**Verify:**
- Run `npm run lint`.
- Run `npx tsc --noEmit -p tsconfig.app.json`.
- Confirm default nodes render at readable width.

### Task 3: Ant Design 6 Compatibility

**Files:**
- Modify: `frontend/src/pages/EditorPage.tsx`
- Modify: `frontend/src/components/DebugDrawer.tsx`

**Steps:**
- Replace `Input` `bordered={false}` with `variant="borderless"`.
- Replace `Drawer` `width={450}` with `size="large"` and style the drawer width through `styles.wrapper`.

**Verify:**
- Run `npm run lint`.
- Run `npm run build`.
