#define WIN32_LEAN_AND_MEAN
#include <windows.h>

static int g_touch_received = 0;
static HFONT g_font = NULL;
static HBRUSH g_background = NULL;

static LRESULT CALLBACK ProbeWindowProc(
    HWND window,
    UINT message,
    WPARAM wparam,
    LPARAM lparam
) {
    (void)lparam;
    switch (message) {
    case WM_CREATE:
        g_font = CreateFontW(
            42, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE,
            DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
            CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE, L"Arial"
        );
        return 0;

    case WM_PAINT: {
        PAINTSTRUCT paint;
        RECT client;
        HDC dc = BeginPaint(window, &paint);
        GetClientRect(window, &client);

        if (g_background != NULL) {
            FillRect(dc, &client, g_background);
        }

        SetBkMode(dc, TRANSPARENT);
        SetTextColor(dc, RGB(235, 245, 255));
        if (g_font != NULL) {
            SelectObject(dc, g_font);
        }

        RECT title = client;
        title.top = client.top + client.bottom / 4;
        title.bottom = title.top + 90;
        DrawTextW(
            dc,
            L"RAMENWINE  /  WAYLAND OK",
            -1,
            &title,
            DT_CENTER | DT_VCENTER | DT_SINGLELINE
        );

        RECT status = client;
        status.top = client.top + client.bottom / 2;
        status.bottom = status.top + 72;
        DrawTextW(
            dc,
            g_touch_received
                ? L"TOUCH INPUT RECEIVED"
                : L"TAP ANYWHERE TO TEST TOUCH",
            -1,
            &status,
            DT_CENTER | DT_VCENTER | DT_SINGLELINE
        );

        RECT footer = client;
        footer.top = client.bottom - 88;
        DrawTextW(
            dc,
            L"ESC  -  CLOSE TEST WINDOW",
            -1,
            &footer,
            DT_CENTER | DT_VCENTER | DT_SINGLELINE
        );

        EndPaint(window, &paint);
        return 0;
    }

    case WM_LBUTTONDOWN:
        g_touch_received = 1;
        InvalidateRect(window, NULL, TRUE);
        return 0;

    case WM_KEYDOWN:
        if (wparam == VK_ESCAPE) {
            DestroyWindow(window);
            return 0;
        }
        break;

    case WM_CLOSE:
        DestroyWindow(window);
        return 0;

    case WM_DESTROY:
        if (g_font != NULL) {
            DeleteObject(g_font);
            g_font = NULL;
        }
        PostQuitMessage(0);
        return 0;

    default:
        break;
    }

    return DefWindowProcW(window, message, wparam, lparam);
}

int WINAPI WinMain(
    HINSTANCE instance,
    HINSTANCE previous_instance,
    LPSTR command_line,
    int show_command
) {
    (void)previous_instance;
    (void)command_line;
    (void)show_command;

    const wchar_t *class_name = L"RamenWineWaylandProbe";
    g_background = CreateSolidBrush(RGB(18, 28, 44));
    if (g_background == NULL) {
        return 2;
    }

    WNDCLASSW window_class = {0};
    window_class.lpfnWndProc = ProbeWindowProc;
    window_class.hInstance = instance;
    window_class.hCursor = LoadCursorW(NULL, IDC_ARROW);
    window_class.hbrBackground = g_background;
    window_class.lpszClassName = class_name;

    if (RegisterClassW(&window_class) == 0) {
        DeleteObject(g_background);
        g_background = NULL;
        return 3;
    }

    int width = GetSystemMetrics(SM_CXSCREEN);
    int height = GetSystemMetrics(SM_CYSCREEN);
    if (width <= 0 || height <= 0) {
        width = 800;
        height = 600;
    }

    HWND window = CreateWindowExW(
        WS_EX_TOPMOST,
        class_name,
        L"RamenWine Wayland probe",
        WS_POPUP | WS_VISIBLE,
        0,
        0,
        width,
        height,
        NULL,
        NULL,
        instance,
        NULL
    );
    if (window == NULL) {
        DeleteObject(g_background);
        g_background = NULL;
        return 4;
    }

    ShowWindow(window, SW_SHOW);
    UpdateWindow(window);

    MSG message;
    BOOL message_result;
    while ((message_result = GetMessageW(&message, NULL, 0, 0)) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }

    int exit_code = message_result == -1 ? 5 : (int)message.wParam;
    DeleteObject(g_background);
    g_background = NULL;
    return exit_code;
}
