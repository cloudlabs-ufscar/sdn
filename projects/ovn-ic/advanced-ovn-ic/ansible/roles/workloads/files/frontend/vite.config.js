import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Built on app-vm-1 and served as static files by nginx, which also proxies
// /api/* across the interconnect to AZ2. Relative asset paths so it works behind
// the SSH tunnel as well as directly on the VIP.
export default defineConfig({
  plugins: [react()],
  base: "./",
  build: { outDir: "dist", emptyOutDir: true },
});
