#!/usr/bin/perl
# Claude Radar hook for Claude Code (macOS).
# Called by Claude Code with the event name as the first argument and the event JSON on stdin.
# Runs as an async hook, so it never slows Claude down. Any failure is logged and ignored.
use strict;
use warnings;
use JSON::PP;
use Time::HiRes qw(time);
use File::Path qw(make_path);
use File::Temp qw(tempfile);

my $event = shift @ARGV // '';
my $dir = "$ENV{HOME}/.claude-radar";
my $json = JSON::PP->new->utf8->canonical;

sub log_err {
    my ($msg) = @_;
    if (open(my $fh, '>>', "$dir/hook-errors.log")) {
        print $fh scalar(localtime) . " $event $msg\n";
        close $fh;
    }
}

sub slurp {
    my ($path) = @_;
    open(my $fh, '<:raw', $path) or return undef;
    local $/;
    my $c = <$fh>;
    close $fh;
    return $c;
}

sub short {
    my ($t, $n) = @_;
    return undef unless defined $t && length $t;
    $t =~ s/\s+/ /g;
    $t =~ s/^ | $//g;
    return length($t) > $n ? substr($t, 0, $n - 1) . "\x{2026}" : $t;
}

eval {
    my $cfg = $json->decode(slurp("$dir/config.json") // die "no config\n");
    my $raw = do { local $/; binmode STDIN, ':raw'; <STDIN> } // '';
    my $in = $json->decode($raw);
    my $sid = $in->{session_id} or exit 0;
    $event ||= $in->{hook_event_name} // '';

    my $state_dir = "$dir/state";
    make_path($state_dir);
    (my $safe = $sid) =~ s/[^\w\-]/_/g;
    my $state_file = "$state_dir/$safe.json";
    my $state = { status => '', title => '' };
    if (my $s = slurp($state_file)) { $state = eval { $json->decode($s) } || $state; }

    my ($status, $message);
    if ($event eq 'UserPromptSubmit') {
        $status = 'working';
        if ($in->{session_title}) { $state->{title} = short($in->{session_title}, 60) }
        elsif (!$state->{title}) { $state->{title} = short($in->{prompt}, 60) }
        $message = short($in->{prompt}, 160);
    } elsif ($event eq 'PostToolUse') {
        # Only matters right after a permission prompt: you approved it, so it's working again.
        exit 0 unless ($state->{status} // '') eq 'needs_you';
        $status = 'working';
    } elsif ($event eq 'Notification') {
        $status = 'needs_you';
        $message = short($in->{message}, 200);
    } elsif ($event eq 'Stop') {
        $status = 'finished';
        $message = short($in->{last_assistant_message}, 280);
    } elsif ($event eq 'StopFailure') {
        $status = 'failed';
        $message = short(join(' ', 'Error:', grep { defined } $in->{error}, $in->{error_details}), 200);
    } elsif ($event eq 'SessionEnd') {
        $status = 'ended';
    } else {
        exit 0;
    }

    my $project;
    if (defined $in->{cwd} && length $in->{cwd}) {
        ($project = $in->{cwd}) =~ s{[/\\]+$}{};
        $project =~ s{^.*[/\\]}{};
    }
    # Claude Code on the web: link back to the chat and label it as a cloud session.
    my ($source, $url, $machine) = ('code', undef, $cfg->{machine});
    if (($ENV{CLAUDE_CODE_REMOTE} // '') eq 'true') {
        $source = 'cloud';
        $machine = 'Cloud';
        if (($ENV{CLAUDE_CODE_REMOTE_SESSION_ID} // '') =~ /^cse_(\w+)$/) {
            $url = "https://claude.ai/code/session_$1";
        }
    }
    my %body = (
        session_id => $sid,
        status     => $status,
        source     => $source,
        url        => $url,
        title      => ($state->{title} || $project),
        project    => $project,
        machine    => $machine,
        message    => $message,
        at         => int(time() * 1000),
        reason     => ($event eq 'SessionEnd' ? $in->{reason} : undef),
    );
    delete $body{$_} for grep { !defined $body{$_} } keys %body;

    if ($status eq 'ended') { unlink $state_file }
    else {
        $state->{status} = $status;
        if (open(my $fh, '>:raw', $state_file)) { print $fh $json->encode($state); close $fh }
    }

    my ($tfh, $tmp) = tempfile('radar-XXXXXX', TMPDIR => 1, UNLINK => 1);
    binmode $tfh, ':raw';
    print $tfh $json->encode(\%body);
    close $tfh;
    my $out = qx{/usr/bin/curl -sS -m 8 -X POST \Q$cfg->{url}\E/functions/v1/report -H \Qx-radar-token: $cfg->{token}\E -H 'Content-Type: application/json; charset=utf-8' --data-binary \@\Q$tmp\E 2>&1};
    log_err("curl failed: $out") if $? != 0;
    unlink $tmp;
    1;
} or do {
    log_err($@) if $@;
};
exit 0;
