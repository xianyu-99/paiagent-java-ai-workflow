#!/usr/bin/env python3
"""Test LLM Config Modal functionality"""

from playwright.sync_api import sync_playwright
import time

def test_llm_config():
    with sync_playwright() as p:
        # Run headless=False to see the browser
        browser = p.chromium.launch(headless=False)
        context = browser.new_context()
        page = context.new_page()

        # Enable console logging
        page.on('console', lambda msg: print(f'[Console] {msg.type}: {msg.text}'))

        print("1. Navigating to login page...")
        page.goto('http://localhost:5173')
        page.wait_for_load_state('networkidle')
        time.sleep(2)

        # Login - the form already has default values admin/123
        print("2. Logging in with admin/123...")

        # Find the submit button (primary button with 登录 text)
        login_button = page.locator('button.ant-btn-primary:has-text("登录")')
        login_button.click()

        # Wait for navigation to complete
        page.wait_for_load_state('networkidle')
        time.sleep(3)
        print(f"   Current URL: {page.url}")

        # Find and click the settings button
        print("3. Looking for settings button...")
        time.sleep(2)

        # The LLMConfigModal component renders a button with SettingOutlined icon
        settings_button = page.locator('button:has(.anticon-setting)').first

        print(f"   Settings button count: {settings_button.count()}")

        if settings_button.count() > 0:
            print("4. Clicking settings button...")
            settings_button.click()
            time.sleep(3)

            # Take screenshot to see what happened
            page.screenshot(path='/tmp/step4_after_click.png')
            print("   Screenshot saved: /tmp/step4_after_click.png")

            # Check if modal appeared
            modal = page.locator('.ant-modal-content')
            modal_count = modal.count()
            print(f"   Modal count: {modal_count}")

            if modal_count > 0:
                print("   SUCCESS: LLM Config Modal is visible!")

                # Check modal title
                title = page.locator('.ant-modal-title').first.text_content()
                print(f"   Modal title: {title}")

                # Check for table
                table = page.locator('.ant-table')
                if table.count() > 0:
                    print("   Config table is visible")

                # Check for add button
                add_btn = page.locator('button:has-text("新增配置")')
                if add_btn.count() > 0:
                    print("   '新增配置' button is visible")

                print("\n=== ALL TESTS PASSED ===")
            else:
                print("   Modal did not appear after clicking settings button")
                # Debug: check what elements exist
                print(f"   .ant-modal count: {page.locator('.ant-modal').count()}")
                print(f"   .ant-modal-wrap count: {page.locator('.ant-modal-wrap').count()}")
        else:
            print("   Could not find settings button")

        browser.close()
        print("\nDone!")

if __name__ == "__main__":
    test_llm_config()
