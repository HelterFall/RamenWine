#define WIN32_LEAN_AND_MEAN
#include <windows.h>

static const char WINDOW_CLASS[] = "RamenWineGdiSmokeWindow";
static const char WINDOW_TITLE[] = "RamenWine Win32 GDI smoke test";

static void fill_panel(HDC dc, int left, int top, int right, int bottom, COLORREF color) {
    RECT rect = { left, top, right, bottom };
    HBRUSH brush = CreateSolidBrush(color);
    if (brush) {
        FillRect(dc, &rect, brush);
        DeleteObject(brush);
    }
}

static LRESULT CALLBACK smoke_window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_PAINT: {
        PAINTSTRUCT paint;
        HDC dc = BeginPaint(window, &paint);
        RECT client;
        GetClientRect(window, &client);

        fill_panel(dc, 0, 0, client.right, client.bottom, RGB(18, 25, 42));
        fill_panel(dc, 24, 72, 176, 172, RGB(0, 170, 210));
        fill_panel(dc, 192, 72, 344, 172, RGB(255, 150, 35));
        fill_panel(dc, 360, 72, 512, 172, RGB(125, 205, 105));

        SetBkMode(dc, TRANSPARENT);
        SetTextColor(dc, RGB(255, 255, 255));
        const char *line1 = "RAMENWINE - WIN32 GDI";
        const char *line2 = "If you can read this, PRoot + Box64 + Wine";
        const char *line3 = "created a real Win32 window on the Wayland surface.";
        const char *line4 = "Close this window or use Stop in RamenWine.";
        TextOutA(dc, 24, 24, line1, lstrlenA(line1));
        TextOutA(dc, 24, 192, line2, lstrlenA(line2));
        TextOutA(dc, 24, 216, line3, lstrlenA(line3));
        TextOutA(dc, 24, 272, line4, lstrlenA(line4));

        EndPaint(window, &paint);
        return 0;
    }

    case WM_ERASEBKGND:
        return 1;

    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }

    return DefWindowProcA(window, message, wparam, lparam);
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command_line, int show_command) {
    (void)previous;
    (void)command_line;
    WNDCLASSEXA window_class = { 0 };
    window_class.cbSize = sizeof(window_class);
    window_class.style = CS_HREDRAW | CS_VREDRAW;
    window_class.lpfnWndProc = smoke_window_proc;
    window_class.hInstance = instance;
    window_class.hCursor = LoadCursorA(NULL, IDC_ARROW);
    window_class.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    window_class.lpszClassName = WINDOW_CLASS;

    if (!RegisterClassExA(&window_class)) {
        return 10;
    }

    HWND window = CreateWindowExA(
        0,
        WINDOW_CLASS,
        WINDOW_TITLE,
        WS_OVERLAPPEDWINDOW | WS_VISIBLE,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        560,
        360,
        NULL,
        NULL,
        instance,
        NULL
    );
    if (!window) {
        return 11;
    }

    ShowWindow(window, show_command);
    UpdateWindow(window);

    MSG message;
    while (GetMessageA(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageA(&message);
    }
    return (int)message.wParam;
}
