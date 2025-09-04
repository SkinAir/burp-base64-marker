package burp;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

public class BurpExtender implements IBurpExtender, IContextMenuFactory, IHttpListener {

    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;

    private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;

    // Marker definitions (前缀是带 / 的，后缀不带 /)
    private static final String DECODE_PREFIX = "</@decode>";
    private static final String DECODE_SUFFIX = "<@decode>";
    private static final String ENCODE_PREFIX = "</@encode>";
    private static final String ENCODE_SUFFIX = "<@encode>";

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
        this.helpers = callbacks.getHelpers();

        callbacks.setExtensionName("Base64 Encode/Decode Marker");
        callbacks.registerContextMenuFactory(this);
        callbacks.registerHttpListener(this);
    }

    // 添加右键菜单
    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
        int context = invocation.getInvocationContext();
        if (context != IContextMenuInvocation.CONTEXT_MESSAGE_EDITOR_REQUEST) {
            return Collections.emptyList();
        }
        int[] bounds = invocation.getSelectionBounds();
        if (bounds == null || bounds.length != 2 || bounds[0] == bounds[1]) {
            return Collections.emptyList();
        }
        IHttpRequestResponse[] messages = invocation.getSelectedMessages();
        if (messages == null || messages.length == 0 || messages[0] == null) {
            return Collections.emptyList();
        }

        JMenuItem markDecode = new JMenuItem("Base64 标记解码（插入 </@decode>..<@decode>）");
        JMenuItem markEncode = new JMenuItem("Base64 标记编码（插入 </@encode>.. <@encode>）");

        markDecode.addActionListener(new ReplaceSelectionWithMarkersAction(messages[0], bounds[0], bounds[1], DECODE_PREFIX, DECODE_SUFFIX));
        markEncode.addActionListener(new ReplaceSelectionWithMarkersAction(messages[0], bounds[0], bounds[1], ENCODE_PREFIX, ENCODE_SUFFIX));

        return Arrays.asList(markDecode, markEncode);
    }

    // 在发送请求前处理标记（仅 Repeater）
    @Override
    public void processHttpMessage(int toolFlag, boolean messageIsRequest, IHttpRequestResponse messageInfo) {
        if (!messageIsRequest) return;
        if (toolFlag != IBurpExtenderCallbacks.TOOL_REPEATER) return;

        byte[] req = messageInfo.getRequest();
        if (req == null || req.length == 0) return;

        IRequestInfo reqInfo = helpers.analyzeRequest(req);
        int bodyOffset = reqInfo.getBodyOffset();

        byte[] headersBytes = Arrays.copyOfRange(req, 0, bodyOffset);
        byte[] bodyBytes = Arrays.copyOfRange(req, bodyOffset, req.length);

        byte[] newBody = transformBody(bodyBytes);
        if (Arrays.equals(bodyBytes, newBody)) {
            return; // 无变化
        }

        // 更新 Content-Length
        List<String> headers = new ArrayList<>(reqInfo.getHeaders());
        headers = updateContentLength(headers, newBody.length);

        byte[] newReq = helpers.buildHttpMessage(headers, newBody);
        messageInfo.setRequest(newReq);
    }

    // 将选中区域包上标记
    private static class ReplaceSelectionWithMarkersAction implements ActionListener {
        private final IHttpRequestResponse message;
        private final int selStart;
        private final int selEnd;
        private final String prefix;
        private final String suffix;

        ReplaceSelectionWithMarkersAction(IHttpRequestResponse message, int selStart, int selEnd, String prefix, String suffix) {
            this.message = message;
            this.selStart = Math.max(0, selStart);
            this.selEnd = Math.max(selStart, selEnd);
            this.prefix = prefix;
            this.suffix = suffix;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            byte[] req = message.getRequest();
            if (req == null || selStart >= selEnd || selEnd > req.length) return;

            byte[] before = Arrays.copyOfRange(req, 0, selStart);
            byte[] selected = Arrays.copyOfRange(req, selStart, selEnd);
            byte[] after = Arrays.copyOfRange(req, selEnd, req.length);

            byte[] newReq = concat(before, prefix.getBytes(ISO_8859_1), selected, suffix.getBytes(ISO_8859_1), after);
            message.setRequest(newReq);
        }
    }

    // 转换请求体：处理 decode/encode 标记
    private byte[] transformBody(byte[] body) {
        if (body == null || body.length == 0) return body;

        // 使用 ISO-8859-1 字符集进行无损字节到字符映射
        String s = new String(body, ISO_8859_1);

        // 先处理 decode，再处理 encode；多次出现时循环处理
        String afterDecode = replaceWithDecodedContent(s, DECODE_PREFIX, DECODE_SUFFIX);
        String afterEncode = replaceWithEncodedContent(afterDecode, ENCODE_PREFIX, ENCODE_SUFFIX);

        if (afterEncode.equals(s)) {
            return body;
        }
        return afterEncode.getBytes(ISO_8859_1);
    }

    // 处理 </@decode>...<@decode>
    private String replaceWithDecodedContent(String input, String prefix, String suffix) {
        int start = 0;
        StringBuilder out = new StringBuilder(input.length());
        while (true) {
            int p = input.indexOf(prefix, start);
            if (p < 0) {
                out.append(input, start, input.length());
                break;
            }
            int q = input.indexOf(suffix, p + prefix.length());
            if (q < 0) {
                out.append(input, start, input.length());
                break;
            }
            // 前段
            out.append(input, start, p);
            String inner = input.substring(p + prefix.length(), q);
            // 去掉空白再解码
            String cleaned = inner.replaceAll("\\s+", "");
            try {
                byte[] decoded = Base64.getDecoder().decode(cleaned);
                out.append(new String(decoded, ISO_8859_1));
            } catch (IllegalArgumentException ex) {
                // 非法 base64，保持原样（含标记）
                out.append(prefix).append(inner).append(suffix);
            }
            start = q + suffix.length();
        }
        return out.toString();
    }

    // 处理 </@encode>...<@encode>
    private String replaceWithEncodedContent(String input, String prefix, String suffix) {
        int start = 0;
        StringBuilder out = new StringBuilder(input.length());
        while (true) {
            int p = input.indexOf(prefix, start);
            if (p < 0) {
                out.append(input, start, input.length());
                break;
            }
            int q = input.indexOf(suffix, p + prefix.length());
            if (q < 0) {
                out.append(input, start, input.length());
                break;
            }
            // 前段
            out.append(input, start, p);
            String inner = input.substring(p + prefix.length(), q);
            byte[] innerBytes = inner.getBytes(ISO_8859_1);
            String encoded = Base64.getEncoder().encodeToString(innerBytes);
            out.append(encoded);
            start = q + suffix.length();
        }
        return out.toString();
    }

    private static List<String> updateContentLength(List<String> headers, int bodyLen) {
        boolean found = false;
        List<String> newHeaders = new ArrayList<>(headers.size());
        for (String h : headers) {
            if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                newHeaders.add("Content-Length: " + bodyLen);
                found = true;
            } else {
                newHeaders.add(h);
            }
        }
        if (!found) {
            newHeaders.add("Content-Length: " + bodyLen);
        }
        return newHeaders;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
