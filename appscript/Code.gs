
// Google Apps Script backend for Myapk notification queue + Telegram bot +
// user-confirmed camera request/upload flow.
//
// Camera flow:
// Telegram / camera request
//      -> CameraRequests sheet (PENDING)
//      -> Android polls doGet?action=getCameraRequest
//      -> Android shows visible confirmation
//      -> user explicitly allows and presses "Ambil Foto"
//      -> Android uploads the captured JPEG
//      -> Apps Script stores it in Drive and optionally sends it to the
//         authorized Telegram chat.
//
// IMPORTANT:
// - Keep BOT_TOKEN private.
// - Set ALLOWED_CHAT_ID to the Telegram chat allowed to control the bot.
// - The camera is NOT opened by the backend. Android must show the request
//   and the user must explicitly allow/capture it.

const SPREADSHEET_ID = "1eNByaaXoqucQlTYRcfzwghKkpUzuprZG93Bw9HjB9Y";
const BOT_TOKEN = "REPLACE_WITH_NEW_BOT_TOKEN";
const ALLOWED_CHAT_ID = "REPLACE_WITH_YOUR_TELEGRAM_CHAT_ID";
const TELEGRAM_API = "https://api.telegram.org/bot" + BOT_TOKEN;
const TIME_ZONE = "Asia/Jakarta";
const CAMERA_SHEET_NAME = "CameraRequests";
const CAMERA_FOLDER_NAME = "Myapk Camera Uploads";

function doGet(e) {
  try {
    const action = e && e.parameter
      ? String(e.parameter.action || "").trim()
      : "";

    if (action === "getCameraRequest") {
      return getCameraRequest();
    }

    return jsonResponse({
      status: "error",
      message: "Unknown GET action"
    });
  } catch (err) {
    console.error(err);
    return jsonResponse({
      status: "error",
      message: String(err)
    });
  }
}

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse({
        status: "error",
        message: "Empty request"
      });
    }

    const contents = JSON.parse(e.postData.contents);

    if (contents.message || contents.callback_query) {
      handleTelegramWebhook(contents);
      return textResponse("OK");
    }

    if (contents.action === "cameraRequestStatus") {
      updateCameraRequestStatus(contents);
      return jsonResponse({status: "success"});
    }

    if (contents.action === "uploadCameraPhoto") {
      return uploadCameraPhoto(contents);
    }

    handleAndroidRequest(contents);
    return jsonResponse({status: "success"});
  } catch (err) {
    console.error(err);
    return jsonResponse({
      status: "error",
      message: String(err)
    });
  }
}

function handleAndroidRequest(contents) {
  const packageName = String(contents.packageName || "Unknown_App");
  const title = String(contents.title || "Tanpa Judul");
  const message = String(contents.message || "Tanpa Isi");
  const timestamp = contents.timestamp
    ? new Date(Number(contents.timestamp))
    : new Date();

  if (isNaN(timestamp.getTime())) {
    throw new Error("Invalid timestamp");
  }

  const ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  const sheetName = sanitizeSheetName(packageName);
  let sheet = ss.getSheetByName(sheetName);

  if (!sheet) {
    sheet = ss.insertSheet(sheetName);
    sheet.appendRow([
      "Timestamp",
      "packageName",
      "title",
      "message"
    ]);
    sheet.getRange(1, 1, 1, 4).setFontWeight("bold");
  }

  sheet.appendRow([
    timestamp,
    packageName,
    title,
    message
  ]);
}

function handleTelegramWebhook(update) {
  const chatId = getUpdateChatId(update);

  if (!isAllowedChat(chatId)) {
    if (chatId !== null) {
      sendTelegramRequest("sendMessage", {
        chat_id: chatId,
        text: "⛔ Akses ditolak."
      });
    }
    return;
  }

  const ss = SpreadsheetApp.openById(SPREADSHEET_ID);

  if (update.callback_query) {
    const callback = update.callback_query;
    const data = String(callback.data || "");

    sendTelegramRequest("answerCallbackQuery", {
      callback_query_id: callback.id
    });

    if (data === "MENU_NOTIFIKASI") {
      sendPackageMenu(chatId, ss);
      return;
    }

    if (data === "MENU_CAMERA") {
      sendCameraMenu(chatId);
      return;
    }

    if (data === "CAMERA|front" || data === "CAMERA|back") {
      createCameraRequest(
        chatId,
        data.substring("CAMERA|".length)
      );
      return;
    }

    if (data.indexOf("APP|") === 0) {
      const appName = data.substring(4);
      saveState(chatId, "SELECTED_APP", appName);
      sendRangeMenu(chatId, appName);
      return;
    }

    if (data.indexOf("COUNT|") === 0) {
      const parts = data.split("|");
      const appName = parts[1];
      const count = Math.max(
        1,
        Math.min(parseInt(parts[2], 10) || 1, 500)
      );
      fetchAndSendNotif(chatId, appName, count);
      return;
    }

    if (data.indexOf("CUSTOM|") === 0) {
      const appName = data.substring(7);
      saveState(chatId, "AWAITING_COUNT", appName);
      sendTelegramRequest("sendMessage", {
        chat_id: chatId,
        text: "Ketik jumlah notifikasi terakhir untuk " + appName + "."
      });
      return;
    }

    if (data.indexOf("DATE|") === 0) {
      const appName = data.substring(5);
      saveState(chatId, "AWAITING_DATE", appName);
      sendTelegramRequest("sendMessage", {
        chat_id: chatId,
        text:
          "Ketik rentang tanggal dengan format YYYY-MM-DD sampai YYYY-MM-DD.\n" +
          "Contoh: 2026-09-20 sampai 2026-09-24"
      });
      return;
    }

    return;
  }

  if (update.message) {
    const msg = update.message;
    const text = String(msg.text || "").trim();

    const awaitingCount = getState(chatId, "AWAITING_COUNT");
    if (awaitingCount) {
      const count = parseInt(text, 10);

      if (!isNaN(count) && count > 0) {
        deleteState(chatId, "AWAITING_COUNT");
        fetchAndSendNotif(
          chatId,
          awaitingCount,
          Math.min(count, 500)
        );
      } else {
        sendTelegramRequest("sendMessage", {
          chat_id: chatId,
          text: "Masukkan angka yang valid."
        });
      }
      return;
    }

    const awaitingDate = getState(chatId, "AWAITING_DATE");
    if (awaitingDate) {
      const range = parseDateRange(text);

      if (range) {
        deleteState(chatId, "AWAITING_DATE");
        fetchAndSendByDate(
          chatId,
          awaitingDate,
          range.start,
          range.end
        );
      } else {
        sendTelegramRequest("sendMessage", {
          chat_id: chatId,
          text:
            "Format tanggal tidak valid. Gunakan " +
            "YYYY-MM-DD sampai YYYY-MM-DD."
        });
      }
      return;
    }

    if (text === "/start" || text === "/menu") {
      sendMainMenu(chatId);
      return;
    }

    if (/^\/camera(?:@\w+)?\s+(front|back)$/i.test(text)) {
      const match = text.match(
        /^\/camera(?:@\w+)?\s+(front|back)$/i
      );

      if (match) {
        createCameraRequest(
          chatId,
          match[1].toLowerCase()
        );
        return;
      }
    }
  }
}

function sendMainMenu(chatId) {
  sendTelegramRequest("sendMessage", {
    chat_id: chatId,
    text: "Selamat datang di Notification Manager Bot.",
    reply_markup: {
      inline_keyboard: [
        [
          {
            text: "🔔 Notifikasi",
            callback_data: "MENU_NOTIFIKASI"
          }
        ],
        [
          {
            text: "📷 Kamera",
            callback_data: "MENU_CAMERA"
          }
        ]
      ]
    }
  });
}

function sendCameraMenu(chatId) {
  sendTelegramRequest("sendMessage", {
    chat_id: chatId,
    text:
      "Pilih kamera yang ingin diminta.\n\n" +
      "Android akan menampilkan permintaan secara terlihat. " +
      "Foto hanya diambil setelah pengguna menyetujui dan menekan " +
      "\"Ambil Foto\".",
    reply_markup: {
      inline_keyboard: [
        [
          {
            text: "📷 Kamera Depan",
            callback_data: "CAMERA|front"
          }
        ],
        [
          {
            text: "📷 Kamera Belakang",
            callback_data: "CAMERA|back"
          }
        ]
      ]
    }
  });
}

function getCameraSheet() {
  const ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  let sheet = ss.getSheetByName(CAMERA_SHEET_NAME);

  if (!sheet) {
    sheet = ss.insertSheet(CAMERA_SHEET_NAME);
    sheet.appendRow([
      "requestId",
      "status",
      "camera",
      "chatId",
      "createdAt",
      "updatedAt",
      "fileId",
      "fileUrl",
      "fileName"
    ]);
    sheet.getRange(1, 1, 1, 9).setFontWeight("bold");
  }

  return sheet;
}

function createCameraRequest(chatId, camera) {
  if (!isAllowedChat(chatId)) return;

  if (camera !== "front" && camera !== "back") {
    sendTelegramRequest("sendMessage", {
      chat_id: chatId,
      text: "Kamera tidak valid."
    });
    return;
  }

  const lock = LockService.getScriptLock();
  lock.waitLock(10000);

  try {
    const sheet = getCameraSheet();
    const lastRow = sheet.getLastRow();

    // Avoid accumulating several active requests from the same chat.
    if (lastRow > 1) {
      const rows = sheet
        .getRange(2, 1, lastRow - 1, 9)
        .getValues();

      for (let i = rows.length - 1; i >= 0; i--) {
        const row = rows[i];
        const rowChatId = String(row[3] || "");
        const status = String(row[1] || "");

        if (rowChatId === String(chatId) &&
            (status === "PENDING" ||
             status === "OFFERED" ||
             status === "RECEIVED")) {
          sendTelegramRequest("sendMessage", {
            chat_id: chatId,
            text:
              "Masih ada permintaan kamera aktif dengan ID:\n" +
              row[0]
          });
          return;
        }
      }
    }

    const requestId =
      Utilities.getUuid();

    const now = new Date();

    sheet.appendRow([
      requestId,
      "PENDING",
      camera,
      String(chatId),
      now,
      now,
      "",
      "",
      ""
    ]);

    sendTelegramRequest("sendMessage", {
      chat_id: chatId,
      text:
        "Permintaan kamera dibuat.\n\n" +
        "Kamera: " + camera + "\n" +
        "ID: " + requestId + "\n\n" +
        "Menunggu konfirmasi di perangkat Android."
    });
  } finally {
    lock.releaseLock();
  }
}

function getCameraRequest() {
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);

  try {
    const sheet = getCameraSheet();
    const lastRow = sheet.getLastRow();

    if (lastRow <= 1) {
      return jsonResponse({
        status: "success",
        request: null
      });
    }

    const rows = sheet
      .getRange(2, 1, lastRow - 1, 9)
      .getValues();

    // Oldest pending request first.
    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      const status = String(row[1] || "");

      if (status === "PENDING") {
        const rowNumber = i + 2;
        const now = new Date();

        sheet.getRange(rowNumber, 2).setValue("OFFERED");
        sheet.getRange(rowNumber, 6).setValue(now);

        return jsonResponse({
          status: "success",
          request: {
            id: String(row[0]),
            camera: String(row[2]),
            status: "OFFERED"
          }
        });
      }
    }

    return jsonResponse({
      status: "success",
      request: null
    });
  } finally {
    lock.releaseLock();
  }
}

function updateCameraRequestStatus(contents) {
  const requestId = String(contents.requestId || "").trim();
  const status = String(contents.status || "").trim();

  if (!requestId || !status) {
    throw new Error("requestId and status are required");
  }

  const allowedStatuses = [
    "RECEIVED",
    "CANCELLED",
    "COMPLETED",
    "UPLOAD_FAILED"
  ];

  if (allowedStatuses.indexOf(status) === -1) {
    throw new Error("Invalid camera request status");
  }

  const sheet = getCameraSheet();
  const rowNumber = findCameraRequestRow(sheet, requestId);

  if (rowNumber === -1) {
    throw new Error("Camera request not found");
  }

  sheet.getRange(rowNumber, 2).setValue(status);
  sheet.getRange(rowNumber, 6).setValue(new Date());

  if (status === "RECEIVED") {
    const chatId = String(
      sheet.getRange(rowNumber, 4).getValue()
    );

    if (isAllowedChat(chatId)) {
      sendTelegramRequest("sendMessage", {
        chat_id: chatId,
        text:
          "Perangkat telah menyetujui permintaan kamera. " +
          "Menunggu foto diambil oleh pengguna."
      });
    }
  }

  if (status === "CANCELLED") {
    const chatId = String(
      sheet.getRange(rowNumber, 4).getValue()
    );

    if (isAllowedChat(chatId)) {
      sendTelegramRequest("sendMessage", {
        chat_id: chatId,
        text: "Permintaan kamera ditolak/dibatalkan oleh perangkat."
      });
    }
  }
}

function uploadCameraPhoto(contents) {
  const requestId = String(contents.requestId || "").trim();
  const camera = String(contents.camera || "").trim();
  const fileName = String(
    contents.fileName || ("camera_" + requestId + ".jpg")
  ).trim();
  const mimeType = String(
    contents.mimeType || "image/jpeg"
  ).trim();
  const photoBase64 = String(
    contents.photoBase64 || ""
  ).trim();

  if (!requestId || !photoBase64) {
    return jsonResponse({
      status: "error",
      message: "requestId and photoBase64 are required"
    });
  }

  if (camera !== "front" && camera !== "back") {
    return jsonResponse({
      status: "error",
      message: "Invalid camera"
    });
  }

  const sheet = getCameraSheet();
  const rowNumber = findCameraRequestRow(sheet, requestId);

  if (rowNumber === -1) {
    return jsonResponse({
      status: "error",
      message: "Camera request not found"
    });
  }

  const requestStatus = String(
    sheet.getRange(rowNumber, 2).getValue()
  );

  if (requestStatus !== "RECEIVED" &&
      requestStatus !== "OFFERED") {
    return jsonResponse({
      status: "error",
      message:
        "Camera request is not in an uploadable state: " +
        requestStatus
    });
  }

  const bytes = Utilities.base64Decode(photoBase64);
  const blob = Utilities.newBlob(
    bytes,
    mimeType,
    fileName
  );

  const folder = getCameraFolder();
  const file = folder.createFile(blob);

  const now = new Date();

  sheet.getRange(rowNumber, 2).setValue("COMPLETED");
  sheet.getRange(rowNumber, 6).setValue(now);
  sheet.getRange(rowNumber, 7).setValue(file.getId());
  sheet.getRange(rowNumber, 8).setValue(file.getUrl());
  sheet.getRange(rowNumber, 9).setValue(file.getName());

  const chatId = String(
    sheet.getRange(rowNumber, 4).getValue()
  );

  if (isAllowedChat(chatId)) {
    sendTelegramPhoto(
      chatId,
      blob,
      "Foto kamera diterima (" + camera + ")."
    );
  }

  return jsonResponse({
    status: "success",
    requestId: requestId,
    fileId: file.getId(),
    fileUrl: file.getUrl()
  });
}

function findCameraRequestRow(sheet, requestId) {
  const lastRow = sheet.getLastRow();

  if (lastRow <= 1) return -1;

  const ids = sheet
    .getRange(2, 1, lastRow - 1, 1)
    .getValues();

  for (let i = 0; i < ids.length; i++) {
    if (String(ids[i][0]) === String(requestId)) {
      return i + 2;
    }
  }

  return -1;
}

function getCameraFolder() {
  const folders = DriveApp.getFoldersByName(
    CAMERA_FOLDER_NAME
  );

  if (folders.hasNext()) {
    return folders.next();
  }

  return DriveApp.createFolder(
    CAMERA_FOLDER_NAME
  );
}


function sendPackageMenu(chatId, ss) {
  const sheets = ss.getSheets();
  const keyboard = [];

  sheets.forEach(function(sh) {
    const name = sh.getName();

    if (name &&
        sh.getLastRow() >= 1 &&
        name !== CAMERA_SHEET_NAME) {
      keyboard.push([
        {
          text: "📱 " + name,
          callback_data: "APP|" + name
        }
      ]);
    }
  });

  if (keyboard.length === 0) {
    sendTelegramRequest("sendMessage", {
      chat_id: chatId,
      text: "Belum ada notifikasi tersimpan."
    });
    return;
  }

  sendTelegramRequest("sendMessage", {
    chat_id: chatId,
    text: "Pilih packageName yang ingin dilihat:",
    reply_markup: {
      inline_keyboard: keyboard
    }
  });
}

function sendRangeMenu(chatId, appName) {
  const keyboard = [
    [
      {
        text: "5 terakhir",
        callback_data: "COUNT|" + appName + "|5"
      },
      {
        text: "10 terakhir",
        callback_data: "COUNT|" + appName + "|10"
      }
    ],
    [
      {
        text: "Jumlah custom",
        callback_data: "CUSTOM|" + appName
      }
    ],
    [
      {
        text: "Rentang tanggal",
        callback_data: "DATE|" + appName
      }
    ]
  ];

  sendTelegramRequest("sendMessage", {
    chat_id: chatId,
    text:
      "Package: " + appName +
      "\nPilih cara mengambil notifikasi:",
    reply_markup: {
      inline_keyboard: keyboard
    }
  });
}

function fetchAndSendNotif(chatId, appName, count) {
  const sheet =
    SpreadsheetApp
      .openById(SPREADSHEET_ID)
      .getSheetByName(appName);

  if (!sheet) {
    sendTelegramRequest("sendMessage", {
      chat_id: chatId,
      text: "Sheet tidak ditemukan."
    });
    return;
  }

  const lastRow = sheet.getLastRow();

  if (lastRow <= 1) {
    sendTelegramRequest("sendMessage", {
      chat_id: chatId,
      text: "Belum ada log notifikasi."
    });
    return;
  }

  const startRow =
    Math.max(2, lastRow - count + 1);

  const rows =
    sheet.getRange(
      startRow,
      1,
      lastRow - startRow + 1,
      4
    ).getValues();

  rows.reverse();

  sendRows(
    chatId,
    appName,
    rows,
    "Notifikasi terakhir"
  );
}

function fetchAndSendByDate(
  chatId,
  appName,
  startDate,
  endDate
) {
  const sheet =
    SpreadsheetApp
      .openById(SPREADSHEET_ID)
      .getSheetByName(appName);

  if (!sheet) {
    sendTelegramRequest("sendMessage", {
      chat_id: chatId,
      text: "Sheet tidak ditemukan."
    });
    return;
  }

  const lastRow = sheet.getLastRow();

  if (lastRow <= 1) {
    sendTelegramRequest("sendMessage", {
      chat_id: chatId,
      text: "Belum ada log notifikasi."
    });
    return;
  }

  const rows =
    sheet.getRange(
      2,
      1,
      lastRow - 1,
      4
    ).getValues();

  const filtered =
    rows.filter(function(row) {
      const d = new Date(row[0]);

      return !isNaN(d.getTime()) &&
        d >= startDate &&
        d <= endDate;
    }).reverse();

  if (filtered.length === 0) {
    sendTelegramRequest("sendMessage", {
      chat_id: chatId,
      text:
        "Tidak ada notifikasi pada rentang tanggal tersebut."
    });
    return;
  }

  sendRows(
    chatId,
    appName,
    filtered,
    "Notifikasi berdasarkan tanggal"
  );
}

function sendRows(
  chatId,
  appName,
  rows,
  heading
) {
  let text =
    "<b>" +
    escapeHtml(heading) +
    "</b>\n" +
    "<code>" +
    escapeHtml(appName) +
    "</code>\n\n";

  rows.forEach(function(row, index) {
    const time =
      Utilities.formatDate(
        new Date(row[0]),
        TIME_ZONE,
        "dd/MM/yyyy HH:mm:ss"
      );

    text +=
      (index + 1) +
      ". <b>[" +
      time +
      "]</b>\n";

    text +=
      "packageName: <code>" +
      escapeHtml(row[1]) +
      "</code>\n";

    text +=
      "title: " +
      escapeHtml(row[2]) +
      "\n";

    text +=
      "message: " +
      escapeHtml(row[3]) +
      "\n\n";
  });

  sendLongTelegramMessage(
    chatId,
    text
  );
}

function sendLongTelegramMessage(
  chatId,
  text
) {
  const max = 3900;

  for (let i = 0; i < text.length; i += max) {
    sendTelegramRequest("sendMessage", {
      chat_id: chatId,
      text: text.substring(i, i + max),
      parse_mode: "HTML"
    });
  }
}

function parseDateRange(text) {
  const m = text.match(
    /^(\d{4}-\d{2}-\d{2})\s*(?:sampai|to|-)\s*(\d{4}-\d{2}-\d{2})$/i
  );

  if (!m) return null;

  const start =
    new Date(
      m[1] + "T00:00:00+07:00"
    );

  const end =
    new Date(
      m[2] + "T23:59:59.999+07:00"
    );

  if (
    isNaN(start.getTime()) ||
    isNaN(end.getTime()) ||
    start > end
  ) {
    return null;
  }

  return {
    start: start,
    end: end
  };
}

function sanitizeSheetName(packageName) {
  return packageName
    .replace(/[^a-zA-Z0-9_\.]/g, "_")
    .substring(0, 30) ||
    "Unknown_App";
}

function getUpdateChatId(update) {
  if (
    update.callback_query &&
    update.callback_query.message
  ) {
    return String(
      update.callback_query.message.chat.id
    );
  }

  if (
    update.message &&
    update.message.chat
  ) {
    return String(
      update.message.chat.id
    );
  }

  return null;
}

function isAllowedChat(chatId) {
  return (
    chatId !== null &&
    String(chatId) === String(ALLOWED_CHAT_ID)
  );
}

function saveState(
  chatId,
  key,
  value
) {
  PropertiesService
    .getScriptProperties()
    .setProperty(
      "STATE_" + chatId + "_" + key,
      value
    );
}

function getState(
  chatId,
  key
) {
  return PropertiesService
    .getScriptProperties()
    .getProperty(
      "STATE_" + chatId + "_" + key
    );
}

function deleteState(
  chatId,
  key
) {
  PropertiesService
    .getScriptProperties()
    .deleteProperty(
      "STATE_" + chatId + "_" + key
    );
}

function escapeHtml(value) {
  return String(
    value == null ? "" : value
  )
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function textResponse(text) {
  return ContentService
    .createTextOutput(text)
    .setMimeType(
      ContentService.MimeType.TEXT
    );
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(
      JSON.stringify(obj)
    )
    .setMimeType(
      ContentService.MimeType.JSON
    );
}

function sendTelegramRequest(
  method,
  payload
) {
  UrlFetchApp.fetch(
    TELEGRAM_API + "/" + method,
    {
      method: "post",
      contentType: "application/json",
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    }
  );
}

function sendTelegramPhoto(
  chatId,
  blob,
  caption
) {
  UrlFetchApp.fetch(
    TELEGRAM_API + "/sendPhoto",
    {
      method: "post",
      payload: {
        chat_id: String(chatId),
        caption: caption || "",
        photo: blob
      },
      muteHttpExceptions: true
    }
  );
}
