"""Serialize frozen SMS records without altering message text or nullable fields."""
import json
from xml.sax.saxutils import quoteattr


XML_FIELDS = {"sender": "address", "timestamp": "date"}


def xml_attribute(value):
    text = str(value)
    for character in text:
        code = ord(character)
        if not (code in (9, 10, 13) or 0x20 <= code <= 0xD7FF
                or 0xE000 <= code <= 0xFFFD or 0x10000 <= code <= 0x10FFFF):
            raise ValueError("短信包含 XML 1.0 无法表示的字符，请下载 JSON 格式")
    return quoteattr(text, {"\n": "&#10;", "\r": "&#13;", "\t": "&#9;"})


def encode_document(messages, format_name):
    if format_name == "json":
        return (json.dumps(messages, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
                "application/json; charset=utf-8")
    if format_name != "xml":
        raise ValueError("只支持 XML 或 JSON 导出")
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<smses format="sms-test-v1" count="' + str(len(messages)) + '">']
    for message in messages:
        attributes = []
        for key, value in message.items():
            name = XML_FIELDS.get(key, key)
            if value is None:
                attributes.append(name + '_null="true"')
            else:
                attributes.append(name + '=' + xml_attribute(value))
        lines.append("  <sms " + " ".join(attributes) + " />")
    lines.append("</smses>")
    return "\n".join(lines).encode("utf-8"), "application/xml; charset=utf-8"
