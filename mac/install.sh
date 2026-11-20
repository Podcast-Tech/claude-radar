#!/bin/bash
# Claude Radar — macOS setup for Claude Code.
# Connects this Mac to your Radar account and adds background hooks to Claude Code.
#
# Install:    curl -fsSL https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/mac/install.sh | bash
# Uninstall:  curl -fsSL https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/mac/install.sh | RADAR_UNINSTALL=1 bash
set -euo pipefail

SUPABASE_URL="https://dkfiyqacjdsvvbrcsabw.supabase.co"
PUBLISHABLE_KEY="sb_publishable_QYuYaLDjC6862ClbKyhO7w_gDTdHEct"
RAW_BASE="https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/mac"

RADAR_DIR="$HOME/.claude-radar"
CLAUDE_DIR="$HOME/.claude"
SETTINGS="$CLAUDE_DIR/settings.json"
HOOK="$RADAR_DIR/radar-hook.pl"
PERL=/usr/bin/perl
[ -x "$PERL" ] || PERL=$(command -v perl)

say() { printf '%b\n' "$1"; }
green() { say "\033[32m$1\033[0m"; }
red() { say "\033[31m$1\033[0m"; }

# Reads JSON on stdin and prints one top-level string field.
json_get() { "$PERL" -MJSON::PP -e 'local $/; my $d = decode_json(<STDIN>); print $d->{$ARGV[0]} // ""' "$1"; }

# Adds (mode=add) or removes (mode=remove) the radar hooks in settings.json, keeping everything else.
edit_settings() {
    mkdir -p "$CLAUDE_DIR"
    [ -f "$SETTINGS" ] && cp "$SETTINGS" "$SETTINGS.radar-backup"
    "$PERL" -MJSON::PP -e '
        my ($path, $mode, $perl, $hook) = @ARGV;
        my $j = JSON::PP->new->utf8->pretty->canonical;
        my $s = {};
        if (-s $path) { local $/; open my $f, "<:raw", $path or die; $s = $j->decode(<$f>); close $f; }
        my $hooks = $s->{hooks} //= {};
        for my $ev (keys %$hooks) {
            my @groups;
            for my $g (@{ $hooks->{$ev} }) {
                my @kept = grep { join(" ", $_->{command} // "", @{ $_->{args} // [] }) !~ /radar-hook\.pl/ } @{ $g->{hooks} // [] };
                if (@kept) { $g->{hooks} = \@kept; push @groups, $g; }
            }
            if (@groups) { $hooks->{$ev} = \@groups } else { delete $hooks->{$ev} }
        }
        if ($mode eq "add") {
            for my $ev (qw(UserPromptSubmit PostToolUse Notification Stop StopFailure SessionEnd)) {
                my $h = { type => "command", command => qq{"$perl" "$hook" $ev}, async => JSON::PP::true, timeout => 15 };
                my $g = { hooks => [$h] };
                $g->{matcher} = "permission_prompt|elicitation_dialog|elicitation_url_dialog|agent_needs_input" if $ev eq "Notification";
                push @{ $hooks->{$ev} }, $g;
            }
        }
        delete $s->{hooks} unless %$hooks;
        open my $o, ">:raw", "$path.tmp" or die; print $o $j->encode($s); close $o;
        rename "$path.tmp", $path or die;
    ' "$SETTINGS" "$1" "$PERL" "$HOOK"
}

if [ "${RADAR_UNINSTALL:-}" = "1" ]; then
    if [ -f "$RADAR_DIR/config.json" ]; then
        url=$(json_get url < "$RADAR_DIR/config.json")
        tok=$(json_get token < "$RADAR_DIR/config.json")
        curl -fsS -m 10 -X POST "$url/functions/v1/report" -H "x-radar-token: $tok" \
            -H "Content-Type: application/json" -d '{"revoke":true}' >/dev/null || say "Could not revoke this Mac's token online (offline?)."
    fi
    edit_settings remove
    rm -rf "$RADAR_DIR"
    green "Claude Radar removed from Claude Code. (Backup: $SETTINGS.radar-backup)"
    exit 0
fi

say ""
say "\033[36mClaude Radar setup\033[0m"
say "Use the account you created in the Claude Radar app on your phone."
if [ -n "${RADAR_EMAIL:-}" ]; then email="$RADAR_EMAIL"; else printf "Email: "; read -r email < /dev/tty; fi
if [ -n "${RADAR_PASSWORD:-}" ]; then password="$RADAR_PASSWORD"; else
    printf "Password: "; stty -echo < /dev/tty; read -r password < /dev/tty; stty echo < /dev/tty; say ""
fi

# 1. Sign in
login_body=$(EMAIL="$email" PASSWORD="$password" "$PERL" -MJSON::PP -e 'print encode_json({email => $ENV{EMAIL}, password => $ENV{PASSWORD}})')
access_token=$(curl -sS -m 20 -X POST "$SUPABASE_URL/auth/v1/token?grant_type=password" \
    -H "apikey: $PUBLISHABLE_KEY" -H "Content-Type: application/json" -d "$login_body" | json_get access_token)
password=""; login_body=""
if [ -z "$access_token" ]; then
    red "Sign-in failed. Check your email/password, and that you confirmed your email."
    exit 1
fi

# 2. Create a token for this Mac (only its hash is stored online)
machine=$(scutil --get ComputerName 2>/dev/null || hostname -s)
token=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')
hash=$(printf '%s' "$token" | shasum -a 256 | cut -d' ' -f1)
token_body=$(H="$hash" M="$machine" "$PERL" -MJSON::PP -e 'print encode_json({token_hash => $ENV{H}, label => $ENV{M}})')
curl -fsS -m 20 -X POST "$SUPABASE_URL/rest/v1/radar_tokens" \
    -H "apikey: $PUBLISHABLE_KEY" -H "Authorization: Bearer $access_token" -H "Prefer: return=minimal" \
    -H "Content-Type: application/json" -d "$token_body" >/dev/null

# 3. Save config + hook script
mkdir -p "$RADAR_DIR"
chmod 700 "$RADAR_DIR"
U="$SUPABASE_URL" T="$token" M="$machine" "$PERL" -MJSON::PP -e \
    'print JSON::PP->new->utf8->pretty->encode({url => $ENV{U}, token => $ENV{T}, machine => $ENV{M}})' > "$RADAR_DIR/config.json"
chmod 600 "$RADAR_DIR/config.json"
script_dir=""
[ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ] && script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ -n "$script_dir" ] && [ -f "$script_dir/radar-hook.pl" ]; then
    cp "$script_dir/radar-hook.pl" "$HOOK"
else
    curl -fsSL "$RAW_BASE/radar-hook.pl" -o "$HOOK"
fi
chmod 755 "$HOOK"

# 4. Add background hooks to Claude Code's user settings
edit_settings add

# 5. Send a test message to the phone
test_body=$(M="$machine" "$PERL" -MJSON::PP -MTime::HiRes=time -e 'print encode_json({
    session_id => "radar-setup-$ENV{M}", status => "finished", source => "code", machine => $ENV{M},
    title => "Radar connected", message => "$ENV{M} is now reporting Claude Code chats.", at => int(time()*1000) })')
curl -fsS -m 20 -X POST "$SUPABASE_URL/functions/v1/report" -H "x-radar-token: $token" \
    -H "Content-Type: application/json; charset=utf-8" --data-binary "$test_body" >/dev/null

say ""
green "Done. Your phone should show 'Radar connected' now."
say "Restart any open Claude Code sessions so they pick up the new hooks."
[ -f "$SETTINGS.radar-backup" ] && say "Settings backup: $SETTINGS.radar-backup"
exit 0
