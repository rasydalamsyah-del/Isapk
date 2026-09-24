// Google Apps Script backend for Myapk notification queue + Telegram bot.
// IMPORTANT: replace ALLOWED_CHAT_ID with the one Telegram chat ID allowed to use the bot.
// Keep BOT_TOKEN private and rotate it if it has ever been exposed publicly.

const SPREADSHEET_ID = "1eNByaaXoqucQlTYRcfzwghKkpUzuprZG93BwH9JbZ_Y";
const BOT_TOKEN = "REPLACE_WITH_NEW_BOT_TOKEN";
const ALLOWED_CHAT_ID = "REPLACE_WITH_YOUR_TELEGRAM_CHAT_ID";
const TELEGRAM_API = "https://api.telegram.org/bot" + BOT_TOKEN;
const TIME_ZONE = "Asia/Jakarta";

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse({status: "error", message: "Empty request"});
    }

    const contents = JSON.parse(e.postData.contents);

    if (contents.message || contents.callback_query) {
      handleTelegramWebhook(contents);
      return textResponse("OK");
    }

    handleAndroidRequest(contents);
    return jsonResponse({status: "success"});
  } catch (err) {
    console.error(err);
    return jsonResponse({status: "error", message: String(err)});
  }
}

function handleAndroidRequest(contents) {
  const packageName = String(contents.packageName || "Unknown_App");
  const title = String(contents.title || "Tanpa Judul");
  const message = String(contents.message || "Tanpa Isi");
  const timestamp = contents.timestamp ? new Date(Number(contents.timestamp)) : new Date();

  if (isNaN(timestamp.getTime())) {
    throw new Error("Invalid timestamp");
  }

  const ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  const sheetName = sanitizeSheetName(packageName);
  let sheet = ss.getSheetByName(sheetName);

  if (!sheet) {
    sheet = ss.insertSheet(sheetName);
    sheet.appendRow(["Timestamp", "packageName", "title", "message"]);
    sheet.getRange(1, 1, 1, 4).setFontWeight("bold");
  }

  // Keep the original packageName in the data even when the sheet name is sanitized/truncated.
  sheet.appendRow([timestamp, packageName, title, message]);
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
    sendTelegramRequest("answerCallbackQuery", {callback_query_id: callback.id});

    if (data === "MENU_NOTIFIKASI") {
      sendPackageMenu(chatId, ss);
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
      const count = Math.max(1, Math.min(parseInt(parts[2], 10) || 1, 500));
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
        text: "Ketik rentang tanggal dengan format YYYY-MM-DD sampai YYYY-MM-DD.\nContoh: 2026-09-20 sampai 2026-09-24"
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
        fetchAndSendNotif(chatId, awaitingCount, Math.min(count, 500));
      } else {
        sendTelegramRequest("sendMessage", {chat_id: chatId, text: "Masukkan angka yang valid."});
      }
      return;
    }

    const awaitingDate = getState(chatId, "AWAITING_DATE");
    if (awaitingDate) {
      const range = parseDateRange(text);
      if (range) {
        deleteState(chatId, "AWAITING_DATE");
        fetchAndSendByDate(chatId, awaitingDate, range.start, range.end);
      } else {
        sendTelegramRequest("sendMessage", {
          chat_id: chatId,
          text: "Format tanggal tidak valid. Gunakan YYYY-MM-DD sampai YYYY-MM-DD."
        });
      }
      return;
    }

    if (text === "/start" || text === "/menu") {
      sendMainMenu(chatId);
    }
  }
}

function sendMainMenu(chatId) {
  sendTelegramRequest("sendMessage", {
    chat_id: chatId,
    text: "Selamat datang di Notification Manager Bot.",
    reply_markup: {inline_keyboard: [[{text: "🔔 Notifikasi", callback_data: "MENU_NOTIFIKASI"}]]}
  });
}

function sendPackageMenu(chatId, ss) {
  const sheets = ss.getSheets();
  const keyboard = [];
  sheets.forEach(function(sh) {
    const name = sh.getName();
    if (name && sh.getLastRow() >= 1) {
      keyboard.push([{text: "📱 " + name, callback_data: "APP|" + name}]);
    }
  });

  if (keyboard.length === 0) {
    sendTelegramRequest("sendMessage", {chat_id: chatId, text: "Belum ada notifikasi tersimpan."});
    return;
  }

  sendTelegramRequest("sendMessage", {
    chat_id: chatId,
    text: "Pilih packageName yang ingin dilihat:",
    reply_markup: {inline_keyboard: keyboard}
  });
}

function sendRangeMenu(chatId, appName) {
  const keyboard = [
    [
      {text: "5 terakhir", callback_data: "COUNT|" + appName + "|5"},
      {text: "10 terakhir", callback_data: "COUNT|" + appName + "|10"}
    ],
    [{text: "Jumlah custom", callback_data: "CUSTOM|" + appName}],
    [{text: "Rentang tanggal", callback_data: "DATE|" + appName}]
  ];

  sendTelegramRequest("sendMessage", {
    chat_id: chatId,
    text: "Package: " + appName + "\nPilih cara mengambil notifikasi:",
    reply_markup: {inline_keyboard: keyboard}
  });
}

function fetchAndSendNotif(chatId, appName, count) {
  const sheet = SpreadsheetApp.openById(SPREADSHEET_ID).getSheetByName(appName);
  if (!sheet) {
    sendTelegramRequest("sendMessage", {chat_id: chatId, text: "Sheet tidak ditemukan."});
    return;
  }

  const lastRow = sheet.getLastRow();
  if (lastRow <= 1) {
    sendTelegramRequest("sendMessage", {chat_id: chatId, text: "Belum ada log notifikasi."});
    return;
  }

  const startRow = Math.max(2, lastRow - count + 1);
  const rows = sheet.getRange(startRow, 1, lastRow - startRow + 1, 4).getValues();
  rows.reverse();
  sendRows(chatId, appName, rows, "Notifikasi terakhir");
}

function fetchAndSendByDate(chatId, appName, startDate, endDate) {
  const sheet = SpreadsheetApp.openById(SPREADSHEET_ID).getSheetByName(appName);
  if (!sheet) {
    sendTelegramRequest("sendMessage", {chat_id: chatId, text: "Sheet tidak ditemukan."});
    return;
  }

  const lastRow = sheet.getLastRow();
  if (lastRow <= 1) {
    sendTelegramRequest("sendMessage", {chat_id: chatId, text: "Belum ada log notifikasi."});
    return;
  }

  const rows = sheet.getRange(2, 1, lastRow - 1, 4).getValues();
  const filtered = rows.filter(function(row) {
    const d = new Date(row[0]);
    return !isNaN(d.getTime()) && d >= startDate && d <= endDate;
  }).reverse();

  if (filtered.length === 0) {
    sendTelegramRequest("sendMessage", {chat_id: chatId, text: "Tidak ada notifikasi pada rentang tanggal tersebut."});
    return;
  }

  sendRows(chatId, appName, filtered, "Notifikasi berdasarkan tanggal");
}

function sendRows(chatId, appName, rows, heading) {
  let text = "<b>" + escapeHtml(heading) + "</b>\n<code>" + escapeHtml(appName) + "</code>\n\n";

  rows.forEach(function(row, index) {
    const time = Utilities.formatDate(new Date(row[0]), TIME_ZONE, "dd/MM/yyyy HH:mm:ss");
    text += (index + 1) + ". <b>[" + time + "]</b>\n";
    text += "packageName: <code>" + escapeHtml(row[1]) + "</code>\n";
    text += "title: " + escapeHtml(row[2]) + "\n";
    text += "message: " + escapeHtml(row[3]) + "\n\n";
  });

  sendLongTelegramMessage(chatId, text);
}

function sendLongTelegramMessage(chatId, text) {
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
  const m = text.match(/^(\d{4}-\d{2}-\d{2})\s*(?:sampai|to|-)\s*(\d{4}-\d{2}-\d{2})$/i);
  if (!m) return null;

  const start = new Date(m[1] + "T00:00:00+07:00");
  const end = new Date(m[2] + "T23:59:59.999+07:00");
  if (isNaN(start.getTime()) || isNaN(end.getTime()) || start > end) return null;
  return {start: start, end: end};
}

function sanitizeSheetName(packageName) {
  return packageName.replace(/[^a-zA-Z0-9_\.]/g, "_").substring(0, 30) || "Unknown_App";
}

function getUpdateChatId(update) {
  if (update.callback_query && update.callback_query.message) return String(update.callback_query.message.chat.id);
  if (update.message && update.message.chat) return String(update.message.chat.id);
  return null;
}

function isAllowedChat(chatId) {
  return chatId !== null && String(chatId) === String(ALLOWED_CHAT_ID);
}

function saveState(chatId, key, value) {
  PropertiesService.getScriptProperties().setProperty("STATE_" + chatId + "_" + key, value);
}

function getState(chatId, key) {
  return PropertiesService.getScriptProperties().getProperty("STATE_" + chatId + "_" + key);
}

function deleteState(chatId, key) {
  PropertiesService.getScriptProperties().deleteProperty("STATE_" + chatId + "_" + key);
}

function escapeHtml(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function textResponse(text) {
  return ContentService.createTextOutput(text).setMimeType(ContentService.MimeType.TEXT);
}

function jsonResponse(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function sendTelegramRequest(method, payload) {
  UrlFetchApp.fetch(TELEGRAM_API + "/" + method, {
    method: "post",
    contentType: "application/json",
    payload: JSON.stringify(payload),
    muteHttpExceptions: true
  });
}
