import com.sun.net.httpserver.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Leur's Brewed Cafe - web system backend.
 * Pure Java 17+ (no external libraries): run with  `java Server.java`
 * Serves the HTML/CSS/Bootstrap pages from ./public and a JSON API under /api.
 * Data is saved to ./data/store.bin after every change, so accounts, orders and menu survive restarts.
 * (Swap the save()/load() methods for JDBC when you move to a real database.)
 */
public class Server {

    // ------------------------------------------------------------------ models
    static class User implements Serializable { int id; String email, username, passHash, role; }
    static class Item implements Serializable { int id; String name, description, category, image; double price; boolean available = true; }
    static class Order implements Serializable {
        int id, userId, itemId, qty; String username, itemName, itemImage, note, payment, status;
        double unitPrice, total; Instant created;
    }

    // ------------------------------------------------------------------- store
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    static final Path PUBLIC = Paths.get("public").toAbsolutePath().normalize();
    static final double SERVICE_FEE = 10;
    static final List<String> CATEGORIES = List.of("Iced Coffee", "Hot Coffee", "Matcha", "Frappe", "Non-Coffee", "Fritz & Fizz");
    static final List<String> STATUSES = List.of("PLACED", "PREPARING", "READY", "CANCELLED");
    static final List<User> users = new ArrayList<>();
    static final List<Item> items = new ArrayList<>();
    static final List<Order> orders = new ArrayList<>();
    static final Map<String, Integer> sessions = new ConcurrentHashMap<>();
    static final SecureRandom RNG = new SecureRandom();
    static int userSeq = 1, itemSeq = 1, orderSeq = 1;
    static final Path DATA = Paths.get("data", "store.bin");
    static final Path UPLOADS = PUBLIC.resolve("uploads");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    static class ApiError extends RuntimeException {
        final int code; ApiError(int code, String msg) { super(msg); this.code = code; }
    }

    // ------------------------------------------------------------------- main
    public static void main(String[] args) throws Exception {
        if (!load()) { seed(); save(); }
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Server::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Leur's Brewed Cafe running at http://localhost:" + PORT);
        System.out.println("Admin:    admin@leurs.cafe / admin123");
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, path); else statics(ex, path);
        } catch (ApiError e) {
            send(ex, e.code, "{\"error\":" + js(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "{\"error\":\"Server error\"}");
        } finally { ex.close(); }
    }

    // ----------------------------------------------------------------- statics
    static void statics(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/login.html";
        Path file = PUBLIC.resolve(path.substring(1)).normalize();
        if (!file.startsWith(PUBLIC) || !Files.isRegularFile(file)) {
            byte[] nf = "Not found".getBytes();
            secure(ex.getResponseHeaders());
            ex.sendResponseHeaders(404, nf.length); ex.getResponseBody().write(nf); return;
        }
        String n = file.getFileName().toString().toLowerCase();
        String type = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".css") ? "text/css; charset=utf-8"
                : n.endsWith(".js") ? "text/javascript; charset=utf-8" : n.endsWith(".jpg") || n.endsWith(".jpeg") ? "image/jpeg"
                : n.endsWith(".png") ? "image/png" : n.endsWith(".svg") ? "image/svg+xml" : n.endsWith(".webp") ? "image/webp" : "application/octet-stream";
        boolean photo = path.startsWith("/img/") || path.startsWith("/uploads/");
        String etag = "\"" + Long.toHexString(Files.getLastModifiedTime(file).toMillis()) + "-" + Long.toHexString(Files.size(file)) + "\"";
        Headers h = ex.getResponseHeaders();
        h.set("ETag", etag);
        // photos rarely change -> cache for a week; pages/css/js are re-checked (cheap 304) so edits show up immediately
        h.set("Cache-Control", photo ? "public, max-age=604800" : "no-cache");
        if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) { secure(h); ex.sendResponseHeaders(304, -1); return; }
        respond(ex, 200, Files.readAllBytes(file), type, !photo);
    }

    /** Sends bytes, gzip-compressed when the browser supports it and the content is text. */
    static void respond(HttpExchange ex, int code, byte[] data, String type, boolean compress) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        secure(h);
        String enc = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (compress && data.length > 600 && enc != null && enc.contains("gzip")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(data.length / 3);
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo)) { gz.write(data); }
            data = bo.toByteArray();
            h.set("Content-Encoding", "gzip");
            h.add("Vary", "Accept-Encoding");
        }
        ex.sendResponseHeaders(code, data.length);
        ex.getResponseBody().write(data);
    }

    static void secure(Headers h) {
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "SAMEORIGIN");
        h.set("Referrer-Policy", "same-origin");
    }

    // --------------------------------------------------------------------- api
    static void api(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        Map<String, String> f = method.equals("GET") || method.equals("DELETE") ? parse(ex.getRequestURI().getRawQuery()) : parse(body(ex));
        String key = method + " " + path.replaceAll("/\\d+", "/{id}");
        int id = idOf(path);
        Object out;
        switch (key) {
            case "POST /api/register" -> out = register(ex, f);
            case "POST /api/login" -> out = login(ex, f);
            case "POST /api/logout" -> { String sid = sid(ex); if (sid != null) sessions.remove(sid); ex.getResponseHeaders().add("Set-Cookie", "sid=; Path=/; Max-Age=0"); out = ok(); }
            case "GET /api/me" -> out = userJson(auth(ex, null));
            case "POST /api/me" -> out = updateUser(auth(ex, null), f, false);
            case "DELETE /api/me" -> { deleteUser(auth(ex, null).id); out = ok(); }
            case "GET /api/menu" -> out = menuJson(false);
            case "POST /api/orders" -> out = placeOrder(auth(ex, null), f);
            case "GET /api/orders" -> out = ordersFor(auth(ex, null));
            case "DELETE /api/orders/{id}" -> out = cancelOrder(auth(ex, null), id);
            case "GET /api/admin/report" -> { auth(ex, "admin"); out = report(); }
            case "GET /api/admin/orders" -> { auth(ex, "admin"); out = allOrders(); }
            case "POST /api/admin/orders/{id}/status" -> { auth(ex, "admin"); out = setStatus(id, f.get("status")); }
            case "GET /api/admin/accounts" -> { auth(ex, "admin"); out = accounts(f.getOrDefault("q", "")); }
            case "POST /api/admin/accounts" -> { auth(ex, "admin"); out = createAccount(f); }
            case "POST /api/admin/accounts/{id}" -> { auth(ex, "admin"); out = updateUser(find(id), f, true); }
            case "DELETE /api/admin/accounts/{id}" -> { User me = auth(ex, "admin"); if (me.id == id) throw new ApiError(400, "You can't delete your own admin account here."); deleteUser(id); out = ok(); }
            case "GET /api/admin/menu" -> { auth(ex, "admin"); out = menuJson(true); }
            case "POST /api/admin/menu" -> { auth(ex, "admin"); out = saveItem(null, f); }
            case "POST /api/admin/menu/{id}" -> { auth(ex, "admin"); out = saveItem(findItem(id), f); }
            case "DELETE /api/admin/menu/{id}" -> { auth(ex, "admin"); synchronized (Server.class) { Item gone = findItem(id); items.remove(gone); deleteUpload(gone.image); } out = ok(); }
            default -> throw new ApiError(404, "Unknown endpoint");
        }
        if (!method.equals("GET")) save();
        send(ex, 200, js(out));
    }

    // ---------------------------------------------------------------- handlers
    static synchronized Object register(HttpExchange ex, Map<String, String> f) {
        String email = v(f, "email").toLowerCase(), name = v(f, "username"), pw = v(f, "password");
        checkUsername(name, null);
        if (!EMAIL.matcher(email).matches()) throw new ApiError(400, "Enter a valid email address.");
        if (pw.length() < 6) throw new ApiError(400, "Password must be at least 6 characters.");
        if (!pw.equals(v(f, "confirm"))) throw new ApiError(400, "Passwords do not match.");
        if (users.stream().anyMatch(u -> u.email.equals(email))) throw new ApiError(409, "That email is already registered.");
        User u = addUser(email, name, pw, "customer");
        startSession(ex, u);
        return userJson(u);
    }

    /** Signs in with either the email address or the username (not case-sensitive). */
    static synchronized Object login(HttpExchange ex, Map<String, String> f) {
        String who = v(f, "identifier");
        if (who.isEmpty()) who = v(f, "email");     // older forms sent the field as "email"
        User u = signIn(who, v(f, "password"));
        startSession(ex, u);
        return userJson(u);
    }

    /**
     * Finds the account whose email address or username matches and whose password is correct.
     * New usernames are unique; if older data still shares a name, the password tells the accounts apart.
     */
    static User signIn(String who, String pw) {
        String email = who.toLowerCase(Locale.ROOT);
        List<User> matches = users.stream()
                .filter(x -> x.email.equals(email) || x.username.equalsIgnoreCase(who))
                .filter(x -> check(pw, x.passHash))
                .toList();
        if (matches.isEmpty()) throw new ApiError(401, "Incorrect email, username or password.");
        if (matches.size() > 1) throw new ApiError(409, "More than one account uses that username. Sign in with your email address instead.");
        return matches.get(0);
    }

    /** A username needs 2+ characters, can't contain @ (so it is never mistaken for an email) and must not be used by another account. */
    static String checkUsername(String name, User owner) {
        if (name.length() < 2) throw new ApiError(400, "Username must be at least 2 characters.");
        if (name.contains("@")) throw new ApiError(400, "Username can't contain the @ sign.");
        if (users.stream().anyMatch(x -> x != owner && x.username.equalsIgnoreCase(name))) throw new ApiError(409, "That username is already taken.");
        return name;
    }

    static synchronized Object updateUser(User u, Map<String, String> f, boolean admin) {
        String name = v(f, "username"), email = v(f, "email").toLowerCase(), pw = v(f, "password");
        boolean rename = !name.isEmpty() && !name.equals(u.username);
        boolean changeEmail = admin && !email.isEmpty() && !email.equals(u.email);
        // check everything first, so a rejected change leaves the account exactly as it was
        if (rename) checkUsername(name, u);
        if (changeEmail) {
            if (!EMAIL.matcher(email).matches()) throw new ApiError(400, "Enter a valid email address.");
            if (users.stream().anyMatch(x -> x != u && x.email.equals(email))) throw new ApiError(409, "That email is already registered.");
        }
        if (!pw.isEmpty() && pw.length() < 6) throw new ApiError(400, "Password must be at least 6 characters.");
        if (rename) u.username = name;
        if (changeEmail) u.email = email;
        if (!pw.isEmpty()) u.passHash = hash(pw);
        return userJson(u);
    }

    static synchronized void deleteUser(int id) {
        User u = find(id);
        users.remove(u);
        sessions.values().removeIf(x -> x == id);
    }

    static synchronized Object createAccount(Map<String, String> f) {
        String email = v(f, "email").toLowerCase(), name = v(f, "username"), pw = v(f, "password");
        if (!EMAIL.matcher(email).matches() || name.isEmpty() || pw.length() < 6) throw new ApiError(400, "Email, username and a 6+ character password are required.");
        checkUsername(name, null);
        if (users.stream().anyMatch(u -> u.email.equals(email))) throw new ApiError(409, "That email is already registered.");
        return userJson(addUser(email, name, pw, "customer"));
    }

    static synchronized Object saveItem(Item it, Map<String, String> f) {
        boolean isNew = it == null;
        if (isNew) {
            if (v(f, "name").isEmpty()) throw new ApiError(400, "Item name is required.");
            if (v(f, "price").isEmpty()) throw new ApiError(400, "Price is required.");
            if (v(f, "category").isEmpty()) throw new ApiError(400, "Please choose a category.");
            it = new Item(); it.id = itemSeq++; it.image = "logo.jpg"; it.description = "";
        }
        if (f.containsKey("name")) { if (v(f, "name").isEmpty()) throw new ApiError(400, "Item name is required."); it.name = v(f, "name"); }
        if (f.containsKey("price")) {
            try { it.price = Double.parseDouble(v(f, "price")); } catch (NumberFormatException e) { throw new ApiError(400, "Enter a valid price."); }
            if (it.price < 0) throw new ApiError(400, "Price can't be negative.");
        }
        if (f.containsKey("category")) { if (!CATEGORIES.contains(v(f, "category"))) throw new ApiError(400, "Unknown category."); it.category = v(f, "category"); }
        if (f.containsKey("description")) {
            if (v(f, "description").length() > 300) throw new ApiError(400, "Description is too long (max 300 characters).");
            it.description = v(f, "description");
        }
        if (f.containsKey("available")) it.available = Boolean.parseBoolean(v(f, "available"));
        if (!v(f, "image").isEmpty()) {
            String old = it.image;
            it.image = saveImage(it.id, v(f, "image"));
            deleteUpload(old);
        }
        if (isNew) items.add(it);
        return itemJson(it);
    }

    /** Accepts a "data:image/jpeg;base64,..." string (the browser converts the chosen picture to JPEG) and stores it in public/uploads. */
    static String saveImage(int itemId, String dataUrl) {
        String prefix = "data:image/jpeg;base64,";
        if (!dataUrl.startsWith(prefix)) throw new ApiError(400, "Please upload a JPG, PNG or WebP picture.");
        byte[] b;
        try { b = Base64.getDecoder().decode(dataUrl.substring(prefix.length()).replaceAll("\\s", "")); }
        catch (IllegalArgumentException e) { throw new ApiError(400, "That picture couldn't be read."); }
        if (b.length < 4 || b.length > 2_000_000 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8)
            throw new ApiError(400, "Picture must be a valid image under 2 MB.");
        String name = "uploads/item-" + itemId + "-" + System.currentTimeMillis() + ".jpg";
        try { Files.createDirectories(UPLOADS); Files.write(PUBLIC.resolve(name), b); }
        catch (IOException e) { throw new ApiError(500, "Could not save the picture."); }
        return name;
    }
    static void deleteUpload(String image) {
        if (image == null || !image.startsWith("uploads/")) return;
        try { Files.deleteIfExists(PUBLIC.resolve(image).normalize()); } catch (IOException ignored) { }
    }

    static synchronized Object placeOrder(User u, Map<String, String> f) {
        Item it = findItem(intOf(f.get("itemId")));
        if (!it.available) throw new ApiError(400, "Sorry, that item is currently unavailable.");
        int qty = intOf(f.get("qty"));
        if (qty < 1 || qty > 20) throw new ApiError(400, "Quantity must be between 1 and 20.");
        String pay = v(f, "payment");
        if (pay.isEmpty()) throw new ApiError(400, "Please select a mode of payment.");
        Order o = newOrder(u, it, qty, v(f, "note"), pay, "PLACED", Instant.now());
        return orderJson(o);
    }

    static synchronized Object cancelOrder(User u, int id) {
        Order o = orders.stream().filter(x -> x.id == id && x.userId == u.id).findFirst().orElseThrow(() -> new ApiError(404, "Order not found."));
        if (!o.status.equals("PLACED")) throw new ApiError(400, "This order is already being prepared and can't be cancelled.");
        o.status = "CANCELLED";
        return orderJson(o);
    }

    static synchronized Object setStatus(int id, String status) {
        if (status == null || !STATUSES.contains(status)) throw new ApiError(400, "Invalid status.");
        Order o = orders.stream().filter(x -> x.id == id).findFirst().orElseThrow(() -> new ApiError(404, "Order not found."));
        o.status = status;
        return orderJson(o);
    }

    static synchronized Object ordersFor(User u) {
        List<Object> out = new ArrayList<>();
        for (int i = orders.size() - 1; i >= 0; i--) if (orders.get(i).userId == u.id) out.add(orderJson(orders.get(i)));
        return out;
    }

    static synchronized Object allOrders() {
        List<Object> out = new ArrayList<>();
        for (int i = orders.size() - 1; i >= 0 && out.size() < 30; i--) out.add(orderJson(orders.get(i)));
        return out;
    }

    static synchronized Object accounts(String q) {
        List<Object> out = new ArrayList<>();
        for (User u : users) if (q.isBlank() || u.username.toLowerCase().contains(q.toLowerCase()) || u.email.contains(q.toLowerCase())) out.add(userJson(u));
        return out;
    }

    static synchronized Object menuJson(boolean all) {
        List<Object> out = new ArrayList<>();
        for (Item it : items) if (all || it.available) out.add(itemJson(it));
        return out;
    }

    static synchronized Object report() {
        LocalDate today = LocalDate.now();
        double revenue = 0; int count = 0;
        Map<String, Integer> by = new HashMap<>();
        for (Order o : orders) {
            if (o.status.equals("CANCELLED") || !LocalDate.ofInstant(o.created, ZoneId.systemDefault()).equals(today)) continue;
            revenue += o.total; count++; by.merge(o.itemName, o.qty, Integer::sum);
        }
        List<Object> top = new ArrayList<>();
        by.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
                .forEach(e -> top.add(map("name", e.getKey(), "orders", e.getValue())));
        return map("revenue", revenue, "orders", count, "avg", count == 0 ? 0 : revenue / count,
                "menuItems", items.size(), "topSellers", top);
    }

    // ----------------------------------------------------------------- helpers
    static User auth(HttpExchange ex, String role) {
        String sid = sid(ex);
        Integer uid = sid == null ? null : sessions.get(sid);
        if (uid == null) throw new ApiError(401, "Please sign in.");
        User u = find(uid);
        if (role != null && !u.role.equals(role)) throw new ApiError(403, "Not allowed.");
        return u;
    }
    static String sid(HttpExchange ex) {
        String c = ex.getRequestHeaders().getFirst("Cookie");
        if (c == null) return null;
        for (String p : c.split(";")) { p = p.trim(); if (p.startsWith("sid=")) return p.substring(4); }
        return null;
    }
    static void startSession(HttpExchange ex, User u) {
        String sid = UUID.randomUUID().toString() + Long.toHexString(RNG.nextLong());
        sessions.put(sid, u.id);
        ex.getResponseHeaders().add("Set-Cookie", "sid=" + sid + "; Path=/; HttpOnly; SameSite=Lax");
    }
    static synchronized User find(int id) { return users.stream().filter(u -> u.id == id).findFirst().orElseThrow(() -> new ApiError(404, "Account not found.")); }
    static synchronized Item findItem(int id) { return items.stream().filter(i -> i.id == id).findFirst().orElseThrow(() -> new ApiError(404, "Menu item not found.")); }
    static User addUser(String email, String name, String pw, String role) {
        User u = new User(); u.id = userSeq++; u.email = email; u.username = name; u.passHash = hash(pw); u.role = role; users.add(u); return u;
    }
    static Order newOrder(User u, Item it, int qty, String note, String pay, String status, Instant at) {
        Order o = new Order(); o.id = orderSeq++; o.userId = u.id; o.username = u.username; o.itemId = it.id; o.itemName = it.name; o.itemImage = it.image;
        o.qty = qty; o.unitPrice = it.price; o.total = it.price * qty + SERVICE_FEE; o.note = note; o.payment = pay; o.status = status; o.created = at;
        orders.add(o); return o;
    }
    static String hash(String pw) {
        try {
            byte[] salt = new byte[16]; RNG.nextBytes(salt);
            return HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(pbkdf2(pw, salt));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static boolean check(String pw, String stored) {
        try {
            String[] p = stored.split("\\$");
            return java.security.MessageDigest.isEqual(pbkdf2(pw, HexFormat.of().parseHex(p[0])), HexFormat.of().parseHex(p[1]));
        } catch (Exception e) { return false; }
    }
    static byte[] pbkdf2(String pw, byte[] salt) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 60000, 256)).getEncoded();
    }
    static String v(Map<String, String> f, String k) { String s = f.get(k); return s == null ? "" : s.trim(); }
    static int intOf(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { throw new ApiError(400, "Invalid number."); } }
    static int idOf(String path) { for (String s : path.split("/")) if (s.matches("\\d+")) return Integer.parseInt(s); return -1; }
    static Map<String, Object> ok() { return map("ok", true); }

    static Map<String, Object> userJson(User u) { return map("id", u.id, "email", u.email, "username", u.username, "role", u.role); }
    static Map<String, Object> itemJson(Item i) {
        return map("id", i.id, "name", i.name, "description", i.description, "category", i.category, "price", i.price, "image", i.image, "available", i.available);
    }
    static Map<String, Object> orderJson(Order o) {
        return map("id", o.id, "itemId", o.itemId, "itemName", o.itemName, "image", o.itemImage, "qty", o.qty, "unitPrice", o.unitPrice,
                "serviceFee", SERVICE_FEE, "total", o.total, "note", o.note, "payment", o.payment, "status", o.status, "user", o.username, "created", o.created.toString());
    }
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // -------------------------------------------------------------------- I/O
    static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readNBytes(3_000_000), StandardCharsets.UTF_8);
    }
    static Map<String, String> parse(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String val = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, val);
        }
        return m;
    }
    static void send(HttpExchange ex, int code, String json) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        respond(ex, code, json.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", true);
    }
    static String js(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\""); case '\\' -> sb.append("\\\\"); case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r"); case '\t' -> sb.append("\\t");
                    default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
                }
            }
            return sb.append('"').toString();
        }
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?, ?> m) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            m.forEach((k, val) -> j.add(js(String.valueOf(k)) + ":" + js(val)));
            return j.toString();
        }
        if (o instanceof Collection<?> c) { StringJoiner j = new StringJoiner(",", "[", "]"); c.forEach(x -> j.add(js(x))); return j.toString(); }
        return js(o.toString());
    }

    // ------------------------------------------------------------- persistence
    static synchronized void save() {
        try {
            Files.createDirectories(DATA.toAbsolutePath().getParent());
            Path tmp = DATA.resolveSibling("store.tmp");
            try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                o.writeObject(new ArrayList<>(users)); o.writeObject(new ArrayList<>(items)); o.writeObject(new ArrayList<>(orders));
                o.writeInt(userSeq); o.writeInt(itemSeq); o.writeInt(orderSeq);
            }
            Files.move(tmp, DATA, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { System.err.println("Could not save data: " + e); }
    }

    @SuppressWarnings("unchecked")
    static boolean load() {
        if (!Files.exists(DATA)) return false;
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(DATA))) {
            users.addAll((List<User>) in.readObject()); items.addAll((List<Item>) in.readObject()); orders.addAll((List<Order>) in.readObject());
            userSeq = in.readInt(); itemSeq = in.readInt(); orderSeq = in.readInt();
            System.out.println("Loaded saved data: " + users.size() + " accounts, " + items.size() + " menu items, " + orders.size() + " orders");
            return true;
        } catch (Exception e) {
            System.err.println("Could not read saved data (" + e + "); starting fresh.");
            users.clear(); items.clear(); orders.clear(); userSeq = itemSeq = orderSeq = 1;
            return false;
        }
    }

    // -------------------------------------------------------------------- seed
    static void seed() {
        addUser("admin@leurs.cafe", "admin", "admin123", "admin");
        item("Americano", "Bold espresso poured over cold water and ice.", "Iced Coffee", 79, "americano-iced.jpg");
        item("Iced Latte", "Smooth espresso blended with cold fresh milk over ice.", "Iced Coffee", 89, "iced-latte.jpg");
        item("Spanish Latte", "Espresso with chilled milk and sweet condensed milk.", "Iced Coffee", 99, "spanish-latte-iced.jpg");
        item("Americano", "Dark, toasted espresso diluted with hot water.", "Hot Coffee", 79, "americano-hot.jpg");
        item("Spanish Latte", "Steamed milk, espresso, and sweet condensed milk with light froth.", "Hot Coffee", 99, "spanish-latte-hot.jpg");
        item("Caramel Macchiato", "Steamed milk marked with espresso, vanilla, and caramel drizzle.", "Hot Coffee", 119, "caramel-macchiato.jpg");
        item("Matcha Latte", "Japanese green tea whisked with cold milk.", "Matcha", 99, "matcha-latte.jpg");
        item("Sea Salt Matcha", "Matcha latte topped with savory salted cream cold foam.", "Matcha", 109, "sea-salt-matcha.jpg");
        item("Biscoff Matcha", "Matcha latte layered with Lotus Biscoff cookie flavor.", "Matcha", 119, "biscoff-matcha.jpg");
        item("Matcha Frappe", "Blended iced matcha tea topped with whipped cream.", "Frappe", 119, "matcha-frappe.jpg");
        item("Oreo Frappe", "Blended milk and crushed Oreos topped with whipped cream.", "Frappe", 119, "oreo-frappe.jpg");
        item("Chocolate Chips", "Blended chocolate base with crunchy cocoa chips.", "Frappe", 129, "chocolate-chips.jpg");
        item("Iced Chocolate", "Classic chocolate syrup mixed with fresh cold milk.", "Non-Coffee", 89, "iced-chocolate.jpg");
        item("Milky Oreo", "Sweet cold milk blended with crushed Oreo cookies.", "Non-Coffee", 99, "milky-oreo.jpg");
        item("Hershey Choco", "Premium Hershey's chocolate syrup paired with milk.", "Non-Coffee", 109, "hershey-choco.jpg");
        item("Green Apple", "Crisp, bubbly soda with tart green apple syrup.", "Fritz & Fizz", 59, "green-apple.jpg");
        item("Blueberry", "Fizzy sparkling water with sweet blueberry syrup.", "Fritz & Fizz", 59, "blueberry.jpg");
        item("Strawberry", "Refreshing sparkling soda infused with sweet strawberry syrup.", "Fritz & Fizz", 59, "strawberry.jpg");

    }
    static void item(String n, String d, String c, double p, String img) {
        Item i = new Item(); i.id = itemSeq++; i.name = n; i.description = d; i.category = c; i.price = p; i.image = img; items.add(i);
    }
}
