<?php
header('Content-Type: application/json');

$stable_release = [
    "tag_name" => "v1.0.15+16",
    "body" => "StreamVault stable release. Auto-detect server URLs, selectable Xtream live stream format, movie variant deduplication and sorting, and translation/playback improvements.",
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

$beta_release = [
    "tag_name" => "v1.0.15-beta+16",
    "body" => "StreamVault beta release. Auto-detect server URLs, selectable Xtream live stream format, movie variant deduplication and sorting, and translation/playback improvements.",
    "html_url" => "https://demo.cockpit.lol/api/streamvault/",
    "published_at" => "2026-06-16T12:00:00Z",
    "draft" => false,
    "prerelease" => true,
    "assets" => [
        [
            "name" => "StreamVault-beta.apk",
            "browser_download_url" => "https://demo.cockpit.lol/api/streamvault/StreamVault-beta.apk"
        ]
    ]
];

$releases = [
    $stable_release,
    $beta_release
];

echo json_encode($releases, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
