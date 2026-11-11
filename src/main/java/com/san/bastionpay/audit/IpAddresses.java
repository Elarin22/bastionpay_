package com.san.bastionpay.audit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/** IP 문자열 검증과 표기 통일. 같은 주소가 다르게 적혀도(IPv6 축약 등) 같은 값으로 비교하게 한다. */
public final class IpAddresses {

    private IpAddresses() {
    }

    /** IP 리터럴이 아니면(호스트 이름 포함) 비어 있다. DNS 조회는 하지 않는다. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.trim();
        if (!ClientIpResolver.isIp(s)) return Optional.empty();
        try {
            return Optional.of(canonical(InetAddress.getByName(s).getHostAddress()));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /** IPv6 는 RFC 5952 의 짧은 표기(가장 긴 0 구간을 ::)로 통일한다. IPv4 는 그대로 둔다. */
    static String canonical(String address) {
        if (!address.contains(":")) return address;
        int zone = address.indexOf('%');
        String[] parts = (zone >= 0 ? address.substring(0, zone) : address).split(":");
        if (parts.length != 8) return address;   // IPv4 가 섞인 표기 등은 JDK 표기를 그대로 쓴다
        String[] h = new String[8];
        for (int i = 0; i < 8; i++) h[i] = Integer.toHexString(Integer.parseInt(parts[i], 16));
        int bestStart = -1, bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (!h[i].equals("0")) { i++; continue; }
            int j = i;
            while (j < 8 && h[j].equals("0")) j++;
            if (j - i > bestLen) { bestStart = i; bestLen = j - i; }
            i = j;
        }
        if (bestLen < 2) return String.join(":", h);
        String left = String.join(":", java.util.Arrays.copyOfRange(h, 0, bestStart));
        String right = String.join(":", java.util.Arrays.copyOfRange(h, bestStart + bestLen, 8));
        return left + "::" + right;
    }
}
