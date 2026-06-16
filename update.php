<?php
header('Content-Type: application/json');

$stable_release = [
    "tag_name" => "v1.0.14-debug",
    "body" => "Cockpit customized stable release. Implemented centralized PanelURL configurations, protected server URLs, dynamic tab filtering, and secure QR pairing.",
    "html_url" => "https://demo.cockpit.lol/api/streamvault/",
    "published_at" => "2026-06-16T12:00:00Z",
    "draft" => false,
    "prerelease" => false,
    "assets" => [
        [
            "name" => "StreamVault.apk",
            "browser_download_url" => "https://demo.cockpit.lol/api/streamvault/StreamVault.apk"
        ]
    ]
];

$releases = [
    $stable_release
];

echo json_encode($releases, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
