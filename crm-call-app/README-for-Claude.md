# fastAPK — راهنمای ساخت وب‌اپ سازگار (برای Claude)

fastAPK یه پوسته‌ی اندرویدی **خام** (WebView) برای یه سایت/وب‌اپ HTTPS هست. خودش هیچ قابلیت اضافه‌ای روشن نمی‌کنه؛
**هر قابلیت فقط وقتی فعال می‌شه که خود صفحه (سرور) با تگ `<meta>` درخواستش کنه.** وقتی وب‌اپ می‌سازی، این قرارداد رو رعایت کن.

## ۱) درخواست قابلیت‌ها از سمت سرور (داخل `<head>`)
```html
<meta name="theme-color" content="#FAF9F5">                 <!-- رنگ نوار بالا (و پیش‌فرض نوار پایین) -->
<meta name="fastapk-fullscreen" content="true">              <!-- true = تمام‌صفحه (زیر نوارها)، false = معمولی -->
<meta name="fastapk-status-color" content="#FAF9F5">         <!-- اختیاری؛ جایگزین theme-color برای نوار بالا -->
<meta name="fastapk-nav-color" content="#FAF9F5">            <!-- رنگ نوار پایین -->
<meta name="fastapk-features" content="print,download,keep-awake,offline">
```
- تگ‌ها بعد از لود صفحه خونده می‌شن و ذخیره می‌شن، پس از اجرای بعدی از همون ابتدا اعمال می‌شن. فقط روی صفحه‌هایی که آدرسشون با SITE_URL شروع می‌شه اثر دارن.
- رنگ‌ها: `#RRGGBB`. روشن/تیره بودن آیکون‌های نوار خودکار از روشنایی رنگ تعیین می‌شه.
- **حتماً `fastapk-fullscreen` رو صریح بنویس.** رنگ نوارها فقط وقتی `false` باشه اعمال می‌شن؛ با `true` نوارها شفاف‌ان و صفحه زیرشون کشیده می‌شه.

### قابلیت‌های `fastapk-features` (با کاما جدا کن؛ هرچی ننویسی خاموشه)
| مقدار | کار |
|---|---|
| `print` | `window.print()` به پرینتر سیستمی اندروید وصل می‌شه + چاپ مستقیم بلوتوثی ESC/POS (پایین ببین) |
| `download` | لینک‌های دانلود http(s) با DownloadManager تو پوشه‌ی Downloads ذخیره می‌شن (کوکی‌ها هم فرستاده می‌شن). لینک `blob:`/`data:` پشتیبانی نمی‌شه، از لینک مستقیم سرور استفاده کن |
| `keep-awake` | صفحه‌ی گوشی خاموش نمی‌شه (برای صندوق/داشبورد) |
| `offline` | به‌جای صفحه‌ی خطای سفید، صفحه‌ی «اینترنت ندارید» با دکمه‌ی تلاش دوباره |

آپلود فایل (`<input type=file>`) همیشه کار می‌کنه (انتخابگر فایل سیستم)؛ نیازی به اعلام نداره.

## ۲) تشخیص اجرا داخل اپ
بعد از لود، این‌ها تعریف می‌شن:
```js
window.FastAPK            // {v:2, features:[...]}  ← فقط داخل اپ وجود داره
document.documentElement.classList.contains('fastapk')
```

## ۳) ناحیه‌ی امن (وقتی fullscreen=true)
`env(safe-area-inset-*)` داخل WebView قابل‌اعتماد نیست. اپ این دو متغیر CSS رو (بر حسب px) ست می‌کنه؛ همیشه با fallback استفاده کن:
```css
.top{padding-top:var(--fastapk-top,env(safe-area-inset-top,0px))}
.nav{padding-bottom:var(--fastapk-bottom,env(safe-area-inset-bottom,0px))}
```
هر المان `position:fixed` بالا/پایین هم باید همین فاصله رو بگیره. در حالت `fullscreen=false` مقدار هر دو ۰ می‌شه.

## ۴) چاپ (feature `print`)
- `window.print()` معمولی → پنجره‌ی چاپ اندروید (CSS مخصوص `@media print` اعمال می‌شه). اندازه‌ی کاغذ رو اگه متغیر سراسری `S.ps` باشه از اون می‌خونه: `80` (رول ۸۰mm، پیش‌فرض)، `58`، `A5`، `A4`.
- چاپ مستقیم بی‌صدا (پرینتر حرارتی بلوتوثی، بدون پنجره‌ی تأیید): پل `AndroidPrint`
  - `AndroidPrint.pick()` لیست پرینترهای جفت‌شده رو نشون می‌ده و انتخاب رو ذخیره می‌کنه؛ بعدش `window.onPrinter()` صدا زده می‌شه
  - `AndroidPrint.printer()` نام پرینتر انتخاب‌شده یا رشته‌ی خالی
  - `AndroidPrint.raw(base64)` بایت‌های خام ESC/POS رو می‌فرسته؛ نتیجه با `window.onPrintDone(true|false)`
  - فارسی روی ESC/POS متنی خراب می‌شه؛ فیش رو روی `<canvas>` بکش (عرض ۵۷۶ نقطه برای ۸۰mm، ۳۸۴ برای ۵۸mm)، تک‌بیتی کن و با `GS v 0` بفرست. نمونه‌ی کامل: تابع `rawPrint` و `rast` در اپ کاوه برگر (`index.html`)
  - قبل از استفاده بررسی کن: `window.AndroidPrint && AndroidPrint.pick`؛ اگه نبود (مرورگر معمولی) از `window.print()` استفاده کن.

## ۵) محدودیت‌ها و قواعد وب‌اپ
- فقط HTTPS با گواهی معتبر (خطای SSL رد می‌شه). آدرس‌هایی که با SITE_URL شروع نشن بیرون از اپ باز می‌شن (مرورگر/تلگرام/تماس).
- دکمه‌ی Back اندروید `history.back()` می‌کنه؛ برای SPA از hash یا `history.pushState` استفاده کن تا Back درست کار کنه.
- فقط حالت عمودی (portrait). زوم متن ۹۹٪. `localStorage`، IndexedDB و Service Worker کار می‌کنن.
- دوربین: از `<input type=file accept="image/*">` استفاده کن (انتخابگر سیستم گزینه‌ی دوربین هم داره)؛ `capture` تضمین نیست.
- `window.open` و پاپ‌آپ رو استفاده نکن؛ برای دانلود فایل از لینک مستقیم + feature `download`.
- وب‌اپ رو mobile-first و RTL بساز؛ آیتم‌های لمسی حداقل ۴۴px.

## ۶) ساخت APK
GitHub → Actions → Build APK → Run workflow. ورودی‌ها: لینک سایت، اسم اپ، آیکون، (پیش‌فرض‌های تمام‌صفحه/رنگ/زوم فقط تا وقتی صفحه تگ‌های بالا رو نداره استفاده می‌شن).

## ۷) قابلیت `crm` (فقط در این fork)
`fastapk-features` می‌تواند `crm` هم داشته باشد. وقتی روشن است، `window.AndroidCRM` (`ping`, `status`, `askPermissions`, `openAccessibility`, `openAppInfo`, `lastCalls(n)`) کار می‌کند. همه‌ی خروجی‌ها رشته‌ی JSON‌اند؛ خطا به شکل `{"error":"feature_off|no_permission|query|json"}`. بعد از گرفتن مجوزها، `window.onCrmPerms()` صدا زده می‌شود. قبل از استفاده بررسی کن: `window.AndroidCRM && AndroidCRM.ping`.
