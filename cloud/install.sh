#!/bin/bash
# Claude Radar — setup for Claude Code on the web (cloud sessions).
# Runs from the cloud environment's setup script, before Claude starts:
#   curl -fsSL https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main/cloud/install.sh | bash || true
#
# The token is NOT stored here: the environment's network proxy adds the
# x-radar-token header (saved as a network secret for the Supabase host).
# This script never fails the session: any error just skips Radar.

SUPABASE_URL="https://dkfiyqacjdsvvbrcsabw.supabase.co"
RAW_BASE="https://raw.githubusercontent.com/Podcast-Tech/claude-radar/main"
RADAR_DIR="$HOME/.claude-radar"
SETTINGS="$HOME/.claude/settings.json"
HOOK="$RADAR_DIR/radar-hook.pl"
PERL=$(command -v perl) || { echo "radar: perl not found, skipping"; exit 0; }

mkdir -p "$RADAR_DIR" "$HOME/.claude" || exit 0
curl -fsSL -m 20 "$RAW_BASE/mac/radar-hook.pl" -o "$HOOK.tmp" && mv "$HOOK.tmp" "$HOOK" || { echo "radar: download failed"; exit 0; }
chmod 755 "$HOOK"

# Placeholder token: the proxy replaces this header with the real secret.
printf '{"url":"%s","token":"injected-by-environment-proxy","machine":"Cloud"}\n' "$SUPABASE_URL" > "$RADAR_DIR/config.json"

"$PERL" -MJSON::PP -e '
    my ($path, $perl, $hook) = @ARGV;
    my $j = JSON::PP->new->utf8->pretty->canonical;
    my $s = {};
    if (-s $path) { local $/; open my $f, "<:raw", $path or die; $s = eval { $j->decode(<$f>) } || {}; close $f; }
    my $hooks = $s->{hooks} //= {};
    for my $ev (keys %$hooks) {
        my @groups;
        for my $g (@{ $hooks->{$ev} }) {
            my @kept = grep { ($_->{command} // "") !~ /radar-hook\.pl/ } @{ $g->{hooks} // [] };
            if (@kept) { $g->{hooks} = \@kept; push @groups, $g; }
        }
        if (@groups) { $hooks->{$ev} = \@groups } else { delete $hooks->{$ev} }
    }
    for my $ev (qw(UserPromptSubmit PostToolUse Notification Stop StopFailure SessionEnd)) {
        my $h = { type => "command", command => qq{"$perl" "$hook" $ev}, async => JSON::PP::true, timeout => 15 };
        my $g = { hooks => [$h] };
        $g->{matcher} = "permission_prompt|elicitation_dialog|elicitation_url_dialog|agent_needs_input" if $ev eq "Notification";
        push @{ $hooks->{$ev} }, $g;
    }
    open my $o, ">:raw", "$path.tmp" or die; print $o $j->encode($s); close $o;
    rename "$path.tmp", $path or die;
' "$SETTINGS" "$PERL" "$HOOK" || { echo "radar: could not update settings"; exit 0; }

echo "radar: cloud reporting installed"
exit 0
